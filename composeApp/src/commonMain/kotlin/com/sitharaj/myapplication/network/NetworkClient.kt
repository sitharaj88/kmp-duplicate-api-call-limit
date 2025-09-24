package com.sitharaj.myapplication.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

// Shared HttpClient constructed in common code. Platform-specific engines
// are added in Gradle (okhttp for Android, darwin for iOS), so this common
// client will pick them up at runtime.
private val sharedClient: HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    // DuplicateRequestGuard plugin
    install(DuplicateRequestGuard) {
        window = 10.seconds  // 10s dedupe window
        dedupeMethods = setOf(HttpMethod.Get) // normally safe
        includeBodyForMethods = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)
        headerKeysForKey = setOf(HttpHeaders.Authorization) // add others if needed
        cacheNon2xx = false // usually don’t cache errors
    }
}

class ApiClient(val client: HttpClient = sharedClient) {
    /**
     * Fetch an example response from [url].
     * @param url Target URL.
     * @param skipDedup When true, this request will skip the DuplicateRequestGuard
     *                  plugin's deduplication behavior for this single call.
     */
    suspend fun getExample(url: String, skipDedup: Boolean = false): ExampleResponse {
        return client.get(url) {
            if (skipDedup) skipDuplicate()
        }.body()
    }

    // Reified convenience helper named 'fetch'
    suspend inline fun <reified T> fetch(url: String, skipDedup: Boolean = false): T {
        return client.get(url) {
            if (skipDedup) skipDuplicate()
        }.body()
    }

    fun close() {
        try {
            client.close()
        } catch (_: Throwable) {
        }
    }
}

@Serializable
data class ExampleResponse(
    val message: String
)
