package com.kauth.infrastructure

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM symmetric encryption for sensitive configuration values.
 *
 * Used exclusively to encrypt SMTP passwords before they are stored in the
 * database. The raw password is never persisted — only the encrypted ciphertext.
 *
 * Key derivation: The 256-bit AES key is derived from [KAUTH_SECRET_KEY] env var
 * using SHA-256. This means the key is deterministic and does not need to be stored.
 *
 * Ciphertext format (all base64-encoded, separated by "."): iv.ciphertext
 *   - iv: 12-byte GCM nonce (randomly generated per encryption)
 *   - ciphertext: AES-256-GCM encrypted bytes (includes the 128-bit auth tag)
 *
 * Phase 3b note: if KAUTH_SECRET_KEY is not set, encrypt/decrypt will throw.
 * The application will still start — SMTP config will simply be unavailable until
 * the env var is configured. This is intentional: forcing SMTP to be unconfigurable
 * without the key is safer than silently allowing plaintext storage.
 */
object EncryptionService {

    private const val ALGORITHM     = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS  = 128

    private val secretKey: SecretKeySpec? by lazy {
        val raw = System.getenv("KAUTH_SECRET_KEY")
        if (raw.isNullOrBlank()) null
        else {
            // Derive a 256-bit key from the env var via SHA-256
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            SecretKeySpec(keyBytes, KEY_ALGORITHM)
        }
    }

    val isAvailable: Boolean get() = secretKey != null

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     * Returns a base64-encoded "iv.ciphertext" string suitable for DB storage.
     * Throws [IllegalStateException] if KAUTH_SECRET_KEY is not set.
     */
    fun encrypt(plaintext: String): String {
        val key = secretKey ?: error(
            "KAUTH_SECRET_KEY env var is not set. Cannot encrypt SMTP password."
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
            val parts      = encrypted.split(".")
            if (parts.size != 2) return null
            val dec        = Base64.getUrlDecoder()
            val iv         = dec.decode(parts[0])
            val ciphertext = dec.decode(parts[1])

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
