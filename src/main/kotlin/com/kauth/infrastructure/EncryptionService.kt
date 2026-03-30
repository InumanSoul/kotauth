package com.kauth.infrastructure

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM symmetric encryption + HMAC-SHA256 cookie signing.
 *
 * Both keys are derived from [rawSecretKey] using SHA-256 with a domain-specific
 * prefix. Keys are deterministic and do not need to be stored separately.
 *
 * Persistence adapters depend on [com.kauth.domain.port.EncryptionPort] (encrypt/decrypt).
 * Web adapters depend on the concrete class for cookie signing (signCookie/verifyCookie).
 */
class EncryptionService(
    rawSecretKey: String,
) : com.kauth.domain.port.EncryptionPort {
    private val secretKey: SecretKeySpec =
        run {
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(rawSecretKey.toByteArray(Charsets.UTF_8))
            SecretKeySpec(keyBytes, KEY_ALGORITHM)
        }

    private val hmacKey: ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest("mfa-cookie-signing:$rawSecretKey".toByteArray(Charsets.UTF_8))

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     * Returns a base64-encoded "iv.ciphertext" string suitable for DB storage.
     * Throws [IllegalStateException] if no secret key was provided.
     */
    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString(iv)}.${enc.encodeToString(ciphertext)}"
    }

    /**
     * Decrypts an encrypted value produced by [encrypt].
     * Returns null if decryption fails (wrong key, tampered data, or missing secret key).
     */
    override fun decrypt(encrypted: String): String? {
        return try {
            val parts = encrypted.split(".")
            if (parts.size != 2) return null
            val dec = Base64.getUrlDecoder()
            val iv = dec.decode(parts[0])
            val ciphertext = dec.decode(parts[1])

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Cookie signing — MFA pending state protection
    // -------------------------------------------------------------------------

    /**
     * Signs a cookie value with HMAC-SHA256.
     * Returns a string in the format: "{value}.{base64url(hmac)}"
     *
     * The value portion is NOT encrypted — it is only authenticated.
     * Use this for short-lived state tokens where confidentiality is not required
     * but forgery prevention is critical (e.g. MFA pending cookies).
     */
    fun signCookie(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val signature =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
        return "$value.$signature"
    }

    /**
     * Verifies a cookie produced by [signCookie].
     * Returns the original value if the signature is valid, null otherwise.
     *
     * Comparison is done in constant time to prevent timing side-channel attacks.
     */
    fun verifyCookie(signed: String): String? {
        val lastDot = signed.lastIndexOf('.')
        if (lastDot < 0) return null
        val value = signed.substring(0, lastDot)
        val expected = signCookie(value)
        if (signed.length != expected.length) return null
        var diff = 0
        for (i in signed.indices) diff = diff or (signed[i].code xor expected[i].code)
        return if (diff == 0) value else null
    }

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
