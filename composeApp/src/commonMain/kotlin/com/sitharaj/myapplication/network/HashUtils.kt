/*
 * HashUtils.kt
 *
 * Declares platform‑specific hashing utilities used by the deduplication
 * infrastructure.  In common code we declare an `expect` function
 * [HashUtils.sha256Base64] which returns a Base64‑encoded SHA‑256
 * digest of the input `ByteArray`.  Concrete implementations must be
 * provided for each platform in the respective source set (for
 * example in `androidMain` and `iosMain`).
 *
 * To enable request body hashing (for POST/PUT deduplication) you
 * should implement this expect object in your multiplatform project.
 * If you don’t need body hashing, leave [includeBodyHashForMethods]
 * empty in [DedupeConfig], and this utility will not be called.
 */

package com.sitharaj.myapplication.network

/**
 * Platform‑specific cryptographic hashing utilities.
 *
 * On the JVM this is typically implemented using `java.security.MessageDigest`
 * and `java.util.Base64`.  On iOS/Native you can implement it using
 * CommonCrypto via the Kotlin/Native interop layer.  See the
 * platform‑specific source sets for example implementations.
 */
expect object HashUtils {
    /**
     * Compute the SHA‑256 digest of [data] and return the result as a
     * Base64 encoded string (without line breaks).  If your platform
     * doesn’t support cryptographic hashing, you can return an empty
     * string or throw an exception, but note that deduplication of
     * request bodies will not work in that case.
     */
    fun sha256Base64(data: ByteArray): String
}