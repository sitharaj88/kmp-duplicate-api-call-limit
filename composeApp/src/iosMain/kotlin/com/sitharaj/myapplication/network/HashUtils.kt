/*
 * HashUtilsIos.kt
 *
 * iOS/Native implementation of [HashUtils] using CommonCrypto.  This file
 * should be placed in the `iosMain` source set of your KMM project.  It
 * computes a SHA‑256 digest of a `ByteArray` and encodes the result
 * as a Base64 string without line breaks.  The implementation uses
 * Kotlin/Native interop to call `CC_SHA256` from CommonCrypto and
 * convert the resulting digest into an `NSData` for Base64 encoding.
 */

package your.pkg.network

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CommonCrypto.CC_SHA256
import platform.CommonCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

private fun ByteArray.toNSData(): NSData = this.usePinned {
    // The `create` factory returns an autoreleased NSData instance.
    NSData.create(bytes = it.addressOf(0), length = this.size.toULong())
}

actual object HashUtils {
    actual fun sha256Base64(data: ByteArray): String {
        val digestLength = CC_SHA256_DIGEST_LENGTH.toInt()
        val digest = ByteArray(digestLength)
        data.usePinned { dataPinned ->
            digest.usePinned { digestPinned ->
                CC_SHA256(
                    dataPinned.addressOf(0),
                    data.size.toUInt(),
                    digestPinned.addressOf(0)
                )
            }
        }
        val nsData = digest.toNSData()
        // 0u for no options; this yields a single line Base64 encoded string.
        return nsData.base64EncodedStringWithOptions(0u)
    }
}