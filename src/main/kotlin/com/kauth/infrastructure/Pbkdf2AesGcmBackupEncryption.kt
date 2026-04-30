package com.kauth.infrastructure

import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import com.kauth.domain.port.BackupEncryptionPort
import com.kauth.domain.util.SecureTokens
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based envelope encryption for tenant backups.
 *
 * KDF: PBKDF2-HMAC-SHA256 with [PBKDF2_ITERATIONS] iterations and a fresh 16-byte
 * salt per export. Output key is 256 bits.
 * Cipher: AES-256-GCM with a fresh 12-byte nonce per export, 128-bit tag.
 *
 * Why PBKDF2 not scrypt/argon2id: PBKDF2 is JDK-built-in (no extra dep, no
 * fat-jar bloat), and 600k iterations clears OWASP's 2024 password-based KDF
 * recommendation. The `bkp1.` envelope version is reserved so we can swap in
 * scrypt or argon2id later (`bkp2.`) without breaking existing exports.
 *
 * Envelope format (one ASCII line, base64url-no-pad components):
 *   bkp1.{salt}.{nonce}.{ciphertext+tag}
 *
 * The passphrase is treated as a [CharArray] end-to-end so callers can clear it
 * after use; we never copy it into a String inside this class.
 */
class Pbkdf2AesGcmBackupEncryption : BackupEncryptionPort {
    override fun encrypt(
        plaintext: String,
        passphrase: CharArray,
    ): String {
        val salt = SecureTokens.randomBytes(SALT_LENGTH)
        val nonce = SecureTokens.randomBytes(NONCE_LENGTH)
        val key = deriveKey(passphrase, salt)
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val enc = Base64.getUrlEncoder().withoutPadding()
            return "$VERSION_PREFIX${enc.encodeToString(
                salt,
            )}.${enc.encodeToString(nonce)}.${enc.encodeToString(ciphertext)}"
        } finally {
            key.encoded?.fill(0)
        }
    }

    override fun decrypt(
        envelope: String,
        passphrase: CharArray,
    ): BackupDecryptResult {
        if (!envelope.startsWith(VERSION_PREFIX)) {
            val maybeVersion = envelope.substringBefore('.', missingDelimiterValue = "")
            return if (maybeVersion.startsWith("bkp")) {
                BackupDecryptResult.Failure(BackupDecryptionError.UnsupportedVersion(maybeVersion))
            } else {
                BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
            }
        }

        val parts = envelope.removePrefix(VERSION_PREFIX).split('.')
        if (parts.size != 3) {
            return BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
        }

        val (salt, nonce, ciphertext) =
            try {
                val dec = Base64.getUrlDecoder()
                Triple(dec.decode(parts[0]), dec.decode(parts[1]), dec.decode(parts[2]))
            } catch (_: IllegalArgumentException) {
                return BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
            }

        if (salt.size != SALT_LENGTH || nonce.size != NONCE_LENGTH) {
            return BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
        }

        val key = deriveKey(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plaintext = cipher.doFinal(ciphertext)
            BackupDecryptResult.Success(plaintext.toString(Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            BackupDecryptResult.Failure(BackupDecryptionError.WrongPassphrase)
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val raw = factory.generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val VERSION_PREFIX = "bkp1."
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 600_000
        private const val KEY_BITS = 256
        private const val SALT_LENGTH = 16
        private const val NONCE_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val CIPHER = "AES/GCM/NoPadding"
    }
}
