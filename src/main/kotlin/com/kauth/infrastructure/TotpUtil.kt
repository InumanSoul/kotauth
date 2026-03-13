package com.kauth.infrastructure

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP implementation — zero external dependencies.
 *
 * Generates time-based one-time passwords compatible with Google Authenticator,
 * Authy, 1Password, and any other TOTP-compliant app.
 *
 * Parameters (matching Google Authenticator defaults):
 *   - Algorithm: HMAC-SHA1
 *   - Digits: 6
 *   - Period: 30 seconds
 *
 * The shared secret is Base32-encoded for URI/QR code compatibility.
 */
object TotpUtil {

    private const val DIGITS     = 6
    private const val PERIOD     = 30L  // seconds
    private const val SECRET_LEN = 20   // 160-bit secret (HMAC-SHA1 key size)

    // Base32 alphabet (RFC 4648)
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Generates a cryptographically random TOTP secret, Base32-encoded.
     */
    fun generateSecret(): String {
        val bytes = ByteArray(SECRET_LEN)
        SecureRandom().nextBytes(bytes)
        return base32Encode(bytes)
    }

    /**
     * Generates the otpauth:// URI for QR code scanning.
     *
     * Format: otpauth://totp/{issuer}:{account}?secret={secret}&issuer={issuer}&algorithm=SHA1&digits=6&period=30
     */
    fun generateUri(secret: String, accountName: String, issuer: String): String {
        val encodedIssuer  = java.net.URLEncoder.encode(issuer, "UTF-8")
        val encodedAccount = java.net.URLEncoder.encode(accountName, "UTF-8")
        return "otpauth://totp/$encodedIssuer:$encodedAccount" +
            "?secret=$secret" +
            "&issuer=$encodedIssuer" +
            "&algorithm=SHA1" +
            "&digits=$DIGITS" +
            "&period=$PERIOD"
    }

    /**
     * Verifies a TOTP code against the given secret.
     *
     * Allows a ±1 time step window to accommodate clock skew between
     * the server and the user's authenticator app.
     */
    fun verify(secret: String, code: String, timeMillis: Long = System.currentTimeMillis()): Boolean {
        if (code.length != DIGITS || !code.all { it.isDigit() }) return false

        val secretBytes = base32Decode(secret)
        val timeStep    = timeMillis / 1000 / PERIOD

        // Check current step and ±1 window
        for (offset in -1L..1L) {
            val otp = generateOtp(secretBytes, timeStep + offset)
            if (otp == code) return true
        }
        return false
    }

    /**
     * Generates the current TOTP code for a given secret (useful for testing).
     */
    fun generateCode(secret: String, timeMillis: Long = System.currentTimeMillis()): String {
        val secretBytes = base32Decode(secret)
        val timeStep    = timeMillis / 1000 / PERIOD
        return generateOtp(secretBytes, timeStep)
    }

    // -----------------------------------------------------------------------
    // Internal HOTP computation (RFC 4226)
    // -----------------------------------------------------------------------

    private fun generateOtp(secret: ByteArray, counter: Long): String {
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        val mac  = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)

        // Dynamic truncation
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt()     and 0x7F) shl 24) or
                     ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                     ((hash[offset + 2].toInt() and 0xFF) shl  8) or
                      (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % Math.pow(10.0, DIGITS.toDouble()).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    // -----------------------------------------------------------------------
    // Base32 encode/decode (RFC 4648, no padding)
    // -----------------------------------------------------------------------

    fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_CHARS[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_CHARS[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    fun base32Decode(encoded: String): ByteArray {
        val cleaned = encoded.uppercase().replace("=", "")
        val output  = mutableListOf<Byte>()
        var buffer  = 0
        var bitsLeft = 0
        for (c in cleaned) {
            val value = BASE32_CHARS.indexOf(c)
            if (value < 0) continue // skip invalid chars
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }
}
