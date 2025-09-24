/*
 * HashUtilsJvm.kt
 *
 * Platform‑specific implementation of [HashUtils] for the JVM/Android
 * target.  This file should reside in the `androidMain` or `jvmMain`
 * source set of your multiplatform project.  It uses the standard
 * `java.security` and `java.util` APIs to compute a SHA‑256 hash and
 * encode it as Base64 without line breaks.
 */

package com.sitharaj.myapplication.network

import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import java.util.Base64

// Use java.util.Base64 if available (Java 8+).  Android’s
// android.util.Base64 is also available, but java.util is more
// portable across JVM targets.
@RequiresApi(Build.VERSION_CODES.O)
private val base64Encoder: Base64.Encoder = Base64.getEncoder()

actual object HashUtils {
    @RequiresApi(Build.VERSION_CODES.O)
    actual fun sha256Base64(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return base64Encoder.encodeToString(digest)
    }
}