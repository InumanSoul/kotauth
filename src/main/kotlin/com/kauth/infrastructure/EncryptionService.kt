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
 * Used for:
 *   1. Encrypting SMTP passwords at rest (AES-256-GCM).
 *   2. Signing short-lived MFA pending cookies (HMAC-SHA256) — prevents userId
 *      forgery that would let an attacker bypass the MFA challenge step.
 *
 * Key derivation: Both keys are derived from [KAUTH_SECRET_KEY] env var using
 * SHA-256 with a domain-specific prefix. This means keys are deterministic and
 * do not need to be stored separately.
 *
 * If KAUTH_SECRET_KEY is not set, a random HMAC key is generated at startup
 * (cookie signatures won't survive a restart, but they will always be valid
 * within a session — the 5-minute TTL makes this acceptable).
 *
 * Cookie signing format: "{value}.{base64url(hmac)}"
 * Verification strips the signature, recomputes it, and compares in constant time.
 */
object EncryptionService {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private val secretKey: SecretKeySpec? by lazy {
        val raw = System.getenv("KAUTH_SECRET_KEY")
        if (raw.isNullOrBlank()) {
            null
        } else {
            // Derive a 256-bit key from the env var via SHA-256
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            SecretKeySpec(keyBytes, KEY_ALGORITHM)
        }
    }

    /**
     * HMAC-SHA256 key for MFA pending cookie signing.
     * Derived from KAUTH_SECRET_KEY with a domain prefix to isolate it from
     * the AES key. Falls back to a random 32-byte key if the env var is not set
     * (signatures valid only within a single process lifetime).
     */
    private val hmacKey: ByteArray by lazy {
        val raw = System.getenv("KAUTH_SECRET_KEY")
        if (!raw.isNullOrBlank()) {
            MessageDigest
                .getInstance("SHA-256")
                .digest("mfa-cookie-signing:$raw".toByteArray(Charsets.UTF_8))
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
    }

    val isAvailable: Boolean get() = secretKey != null

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     * Returns a base64-encoded "iv.ciphertext" string suitable for DB storage.
     * Throws [IllegalStateException] if KAUTH_SECRET_KEY is not set.
     */
    fun encrypt(plaintext: String): String {
        val key =
            secretKey ?: error(
                "KAUTH_SECRET_KEY env var is not set. Cannot encrypt SMTP password.",
            )

        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString(iv)}.${enc.encodeToString(ciphertext)}"
    }

    /**
     * Decrypts an encrypted value produced by [encrypt].
     * Returns null if decryption fails (wrong key, tampered data, or missing env var).
     */
    fun decrypt(encrypted: String): String? {
        val key = secretKey ?: return null
        return try {
            val parts = encrypted.split(".")
            if (parts.size != 2) return null
            val dec = Base64.getUrlDecoder()
            val iv = dec.decode(parts[0])
            val ciphertext = dec.decode(parts[1])

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Cookie signing — MFA pending state protection (Phase 3c security fix)
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
        // Constant-time comparison — do not short-circuit on first mismatch
        if (signed.length != expected.length) return null
        var diff = 0
        for (i in signed.indices) diff = diff or (signed[i].code xor expected[i].code)
        return if (diff == 0) value else null
    }
}
