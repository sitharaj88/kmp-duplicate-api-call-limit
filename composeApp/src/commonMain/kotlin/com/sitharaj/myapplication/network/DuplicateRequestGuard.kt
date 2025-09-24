@file:Suppress("unused")

package com.sitharaj.myapplication.network

import com.sitharaj.myapplication.DedupeLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.collections.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.ktor.content.* // For OutgoingContent impls when available; on newer Ktor this is in io.ktor.http.content.*
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodeURLQueryComponent


// ---------- Public API ----------

/**
 * Helper attribute that can be attached to an [HttpRequestBuilder] to control
 * deduplication behavior for that single request.
 *
 * This class is intentionally small and immutable; it can be stored in the
 * request attributes map and read by the [DuplicateRequestGuard] plugin during
 * request processing.
 *
 * Usage (Kotlin):
 * requestBuilder.duplicateKey(keyOverride = "my-key")
 *
 * From Java, use the attribute key constant `DuplicateKey.Key` to put/get an
 * instance on the request attributes map.
 *
 * @property keyOverride Optional explicit key string. When provided, the plugin
 *   will use this value as the identity for dedupe/caching and will ignore the
 *   normal computed key (method/url/headers/body). This is useful when the
 *   request identity depends on application-level information.
 * @property includeBody If non-null, overrides the plugin's default decision for
 *   including the request body in the identity. `true` forces body inclusion,
 *   `false` forces body exclusion. When null, the plugin uses its configured
 *   method-based defaults.
 * @property dedupe When `false`, disables deduplication for this request even
 *   if the plugin would otherwise consider it. When `true`, plugin behavior is
 *   unmodified by this flag.
 */
class DuplicateKey(
    val keyOverride: String? = null,
    val includeBody: Boolean? = null,
    val dedupe: Boolean = true
) {
    companion object {
        val Key: AttributeKey<DuplicateKey> = AttributeKey("DuplicateKey")
    }
}

/**
 * A client plugin that prevents duplicate HTTP requests from executing
 * repeatedly in a short window. DuplicateRequestGuard performs two distinct
 * optimizations:
 *
 * 1. In-flight coalescing: concurrent requests that resolve to the same
 *    computed identity will be coalesced so only the first actual network
 *    request is executed; the remaining callers receive a readable copy of the
 *    original response.
 *
 * 2. Short-lived caching: successful responses (and optionally non-2xx results)
 *    are stored for a small time window so immediate subsequent requests can
 *    be served from the cache instead of hitting the network.
 *
 * The plugin is safe for use in KMP environments (no JVM-only APIs) and aims to
 * be conservative about replaying request bodies — only bodies that are
 * representable as replayable content will be included in the identity.
 *
 * Configure with [Config] and install through the standard Ktor plugin
 * mechanism. Example:
 *
 * val client = HttpClient { install(DuplicateRequestGuard) { window = 5.seconds } }
 *
 * @param window Deduplication window duration: how long a completed request's
 *   result is kept in the short cache. The plugin also treats requests made
 *   concurrently (in-flight) as duplicates regardless of this window.
 * @param dedupeMethods Set of HTTP methods that should be deduped by default.
 *   Requests using these methods are eligible for coalescing and window cache.
 * @param includeBodyForMethods Methods for which the request body should be
 *   included in the computed identity when possible. Useful for idempotent
 *   non-GET methods where payload matters (e.g., idempotent POSTs).
 * @param headerKeysForKey A case-insensitive set of header names that will be
 *   included in the request identity. Keep this list small; headers are
 *   normalized to lowercase prior to inclusion.
 * @param cacheNon2xx When true, non-successful responses (non-2xx) are cached
 *   in the short window alongside successful responses. When false (default),
 *   only 2xx and 304 (Not Modified) responses are considered ok-to-cache.
 */
class DuplicateRequestGuard private constructor(
    private val window: Duration,
    private val dedupeMethods: Set<HttpMethod>,
    private val includeBodyForMethods: Set<HttpMethod>,
    private val headerKeysForKey: Set<String>,
    private val cacheNon2xx: Boolean
) {

    /**
     * Configuration holder for [DuplicateRequestGuard]. Set the fields you want
     * to change; sensible defaults are provided.
     */
    class Config {
        /**
         * Treat requests with the same identity made within this window as duplicates.
         * Includes both: (a) concurrently in-flight requests and (b) immediately
         * repeated requests after the original has finished.
         */
        var window: Duration = 10.seconds

        /**
         * HTTP methods deduped by default (concurrent + window). GET/HEAD/OPTIONS
         * are commonly safe to dedupe; modify this set to suit your API semantics.
         */
        var dedupeMethods: Set<HttpMethod> =
            setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

        /**
         * For these methods, the request body (when representable) is included
         * in the identity. This is useful for idempotent POST/PUT/PATCH calls
         * where the body changes the request semantics.
         */
        var includeBodyForMethods: Set<HttpMethod> =
            setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)

        /**
         * Headers added to identity. Case-insensitive, stored normalized
         * (lowercase). Keep this list small and deterministic because adding
         * many headers increases the likelihood of cache misses.
         */
        var headerKeysForKey: Set<String> =
            setOf(HttpHeaders.Authorization, HttpHeaders.ContentType, HttpHeaders.AcceptLanguage)
                .map { it.lowercase() }.toSet()

        /**
         * If true, cache errors/non-2xx responses within the window as well. Use
         * this cautiously: caching failures can hide transient backend issues.
         */
        var cacheNon2xx: Boolean = false

        fun build() = DuplicateRequestGuard(
            window, dedupeMethods, includeBodyForMethods, headerKeysForKey, cacheNon2xx
        )
    }

    companion object : HttpClientPlugin<Config, DuplicateRequestGuard> {
        override val key: AttributeKey<DuplicateRequestGuard> = AttributeKey("DuplicateRequestGuard")

        override fun prepare(block: Config.() -> Unit): DuplicateRequestGuard {
            val cfg = Config().apply(block)
            return cfg.build()
        }

        override fun install(plugin: DuplicateRequestGuard, scope: HttpClient) {
            // In-flight registry: requestKey -> deferred call
            val inFlight = ConcurrentMap<String, CompletableDeferred<HttpClientCall>>()

            // Tiny time-bounded cache: requestKey -> (call, deadline)
            val cacheMutex = Mutex()
            data class Cached(val call: HttpClientCall, val deadline: TimeSource.Monotonic.ValueTimeMark)
            val cache = mutableMapOf<String, Cached>()
            val clock = TimeSource.Monotonic

            fun purgeExpired(now: TimeSource.Monotonic.ValueTimeMark) {
                val it = cache.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (now >= e.value.deadline) it.remove()
                }
            }

            scope.sendPipeline.intercept(HttpSendPipeline.State) {
                val req = context
                val method = req.method

                // Per-request overrides
                val overrides = req.attributes.getOrNull(DuplicateKey.Key)

                // Skip entirely if dedupe disabled for this request
                val shouldDedupe = overrides?.dedupe ?: true
                if (!shouldDedupe) {
                    proceed()
                    return@intercept
                }

                // Dedupe only for configured methods (unless overridden with keyOverride/includeBody)
                val methodDedupe = method in plugin.dedupeMethods || method in plugin.includeBodyForMethods
                if (!methodDedupe) {
                    proceed()
                    return@intercept
                }

                // Build robust identity
                val key = plugin.buildRequestKey(req, overrides)

                // Quick hit: small 10s cache (or configured window)
                val now = clock.markNow()
                val cached = cacheMutex.withLock {
                    purgeExpired(now)
                    cache[key]
                }
                if (cached != null) {
                    // Each consumer gets its own readable copy
                    // Log that we served from cache
                    try {
                        DedupeLogger.log("DuplicateRequestGuard: served cached for key=$key")
                    } catch (_: Throwable) { }
                    proceedWith(cached.call.save())
                    return@intercept
                }

                // Coalesce concurrent duplicates
                val existing = inFlight[key]
                if (existing != null) {
                    // Wait for the first identical request; return a saved copy
                    val winnerCall = existing.await()
                    try {
                        DedupeLogger.log("DuplicateRequestGuard: coalesced concurrent for key=$key")
                    } catch (_: Throwable) { }
                    proceedWith(winnerCall.save())
                    return@intercept
                }

                // First requester: create placeholder and execute
                val deferred = CompletableDeferred<HttpClientCall>()
                inFlight[key] = deferred
                try {
                    // Log that we're performing the actual request for this key
                    try {
                        DedupeLogger.log("DuplicateRequestGuard: performing request for key=$key")
                    } catch (_: Throwable) { }

                    proceed() // executes the request, sets subject to HttpClientCall
                    val call = subject as HttpClientCall

                    // Decide if we should cache this result for the window
                    val status = call.response.status
                    val okToCache = plugin.cacheNon2xx || status.isSuccess() || status == HttpStatusCode.NotModified

                    if (okToCache) {
                        cacheMutex.withLock {
                            purgeExpired(now)
                            cache[key] = Cached(call, now + plugin.window)
                        }
                    }

                    deferred.complete(call)
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                    throw t
                } finally {
                    inFlight.remove(key, deferred)
                }
            }
        }
    }

    // ---------- Key building ----------

    /**
     * Compute a compact key for the given request. The key is a SHA-256 base64
     * of a structured string that includes method, canonical URL, selected
     * headers, and optionally a hash of the request body.
     *
     * @param req The [HttpRequestBuilder] being sent.
     * @param overrides Optional [DuplicateKey] instance attached to the request
     *   that can override behavior for this single call (key override, body
     *   inclusion, or disabling dedupe).
     * @return A stable, compact string identifying the request for dedupe/caching.
     */
    private suspend fun buildRequestKey(
        req: HttpRequestBuilder,
        overrides: DuplicateKey?
    ): String {
        // If caller provided their own key, honor it entirely
        overrides?.keyOverride?.let { return "k:$it" }

        val includeBody = overrides?.includeBody ?: (req.method in includeBodyForMethods)

        val urlPart = canonicalUrl(req.url.build())
        val methodPart = req.method.value

        val headersPart = buildHeadersPart(req.headers, headerKeysForKey)

        val bodyPart = if (includeBody) (hashBodyIfPossible(req.body) ?: "no-body") else "body-off"

        // Final key => SHA-256 of structured string to keep it compact
        val packed = "m=$methodPart|u=$urlPart|h=$headersPart|b=$bodyPart"
        return sha256Base64(packed)
    }

    /**
     * Build a canonical URL string consisting of scheme, host, port (when
     * non-default), path and a deterministic, sorted query-string. This ensures
     * semantically equivalent URLs with different query ordering map to the
     * same identity.
     *
     * Example output: "https://api.example.com:8443/path/to/resource?a=1&b=2"
     *
     * @param url The [Url] to canonicalize.
     * @return A deterministic string representation of the URL suitable for
     *   inclusion in the dedupe key.
     */
    private fun canonicalUrl(url: Url): String {
        // scheme://host:port/path?sortedQuery
        val builder = StringBuilder()
        builder.append(url.protocol.name.lowercase()).append("://")
        builder.append(url.host.lowercase())
        if (!(url.protocol.defaultPort == url.port || (url.port == -1 && url.protocol.defaultPort == 443))) {
            builder.append(":").append(url.port)
        }
        builder.append(url.encodedPath) // already canonical (leading '/')

        if (url.parameters.isEmpty()) return builder.toString()

        val sorted = url.parameters.entries()
            .flatMap { (k, vs) -> vs.map { v -> k to v } }
            .sortedWith(compareBy({ it.first.lowercase() }, { it.second }))

        builder.append("?")
        sorted.joinTo(builder, "&") { (k, v) ->
            "${k.encodeURLQueryComponent()}=${v.encodeURLQueryComponent()}"
        }
        return builder.toString()
    }

    /**
     * Build a deterministic headers string limited to the keys listed in
     * [include]. Header names are normalized to lowercase and values are
     * sorted. Returns a compact representation or marker tokens when the set is
     * empty.
     *
     * @param headers The request headers to inspect.
     * @param include The header names (lowercased) to include in the key.
     * @return A compact deterministic string representing the selected headers.
     */
    private fun buildHeadersPart(headers: HeadersBuilder, include: Set<String>): String {
        if (include.isEmpty()) return "h-off"
        val wanted = include.mapNotNull { k ->
            val values = headers.getAll(k) ?: headers.getAll(k.lowercase()) ?: return@mapNotNull null
            k.lowercase() to values.sorted()
        }.sortedBy { it.first }
        if (wanted.isEmpty()) return "h-none"
        val sb = StringBuilder()
        wanted.forEach { (k, vs) ->
            sb.append(k).append("=")
            vs.joinTo(sb, ",")
            sb.append(";")
        }
        return sb.toString()
    }

    /**
     * Attempt to produce a stable hash for the request body when the body is a
     * replayable/outgoing content type. For non-replayable or streaming bodies
     * this returns null and the plugin will fall back to not including the
     * body in the identity (unless the caller forced body inclusion via
     * [DuplicateKey]).
     *
     * Notes:
     * - Reading channel-based content will consume it; this implementation reads
     *   into memory to compute a hash which may be unsuitable for very large
     *   payloads. Prefer ByteArrayContent/TextContent for deduped calls.
     * - Multipart/form-data is intentionally not hashed (returns null) because
     *   boundary/platform differences make it non-portable across KMP targets.
     *
     * @param body The request body object from [HttpRequestBuilder].
     * @return A base64 SHA-256 string for representable bodies, the string
     *   "empty" for explicit no-content, or null when the body cannot be
     *   safely hashed/replayed.
     */
    private suspend fun hashBodyIfPossible(body: Any): String? = when (body) {
        is OutgoingContent.ByteArrayContent -> sha256Base64(body.bytes())
        is OutgoingContent.ReadChannelContent -> runCatching {
            // Clone the content by reading fully into bytes; not ideal for very large bodies.
            // Prefer ByteArrayContent/TextContent for deduped calls.
            val channel = body.readFrom()
            val packet = channel.readRemaining()
            val bytes = packet.readBytes()
            sha256Base64(bytes)
        }.getOrNull()
        is OutgoingContent.WriteChannelContent -> null // can't replay safely
        is OutgoingContent.NoContent -> "empty"
        is TextContent -> sha256Base64(body.text.encodeToByteArray())
        is ByteArrayContent -> sha256Base64(body.bytes())
        is FormDataContent -> {
            // Canonicalize form fields (order-independent)
            val pairs = body.formData.entries().flatMap { (name, values) ->
                values.map { v -> name to v }
            }.sortedBy { it.first }
            sha256Base64(pairs.joinToString("&") { "${it.first}=${it.second}" }.encodeToByteArray())
        }
        is MultiPartFormDataContent -> null // not stable to hash here portable across KMP
        else -> null
    }

    // ---------- tiny SHA256 util (no JVM-only APIs) ----------

    /**
     * Compute a SHA-256 digest of the supplied bytes and return a base64
     * representation. Uses the multiplatform [HashUtils] helper in the project.
     *
     * @param bytes Input bytes to hash.
     * @return base64-encoded sha256 digest as a compact identity segment.
     */
    private fun sha256Base64(bytes: ByteArray): String {
        val digest = HashUtils.sha256Base64(bytes)
        return digest.encodeBase64()
    }

    private fun sha256Base64(s: String): String = sha256Base64(s.encodeToByteArray())
}

// ---------- Convenience helpers ----------

/**
 * Per-request helper to set/override dedupe behavior.
 *
 * This extension stores a [DuplicateKey] in the request's attributes so the
 * [DuplicateRequestGuard] plugin can read per-request overrides. From Java
 * call-sites, construct a [DuplicateKey] and put it into the request's
 * attributes using `DuplicateKey.Key`.
 *
 * @param keyOverride Optional explicit key to use instead of the computed one.
 * @param includeBody If non-null, forces inclusion/exclusion of the request
 *   body in the identity for this request.
 * @param dedupe When false, disables deduplication for this single request.
 */
fun HttpRequestBuilder.duplicateKey(
    keyOverride: String? = null,
    includeBody: Boolean? = null,
    dedupe: Boolean = true
) {
    attributes.put(DuplicateKey.Key, DuplicateKey(keyOverride, includeBody, dedupe))
}
