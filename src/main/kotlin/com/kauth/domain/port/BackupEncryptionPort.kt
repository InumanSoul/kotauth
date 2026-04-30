package com.kauth.domain.port

/**
 * Port (outbound) — passphrase-derived envelope encryption for tenant backups.
 *
 * The contract is intentionally narrow: caller hands in plaintext + passphrase,
 * gets back a self-describing string envelope (KDF salt + nonce + ciphertext +
 * GCM tag, with a `bkp1.` sentinel prefix). Decryption is the inverse.
 *
 * The passphrase is operator-supplied per-invocation. It is never stored, never
 * logged, and never derived from `KAUTH_SECRET_KEY` — a leaked server secret
 * must not unlock backups taken before the leak.
 *
 * Implementations must:
 *   - Use a memory-hard KDF (scrypt or argon2id) with per-export random salt
 *   - Use AES-256-GCM with a per-export random 96-bit nonce
 *   - Include the algorithm version in the envelope so we can rotate KDFs later
 *   - Fail with [BackupDecryptionError.WrongPassphrase] on tag mismatch (do NOT
 *     leak whether the envelope was malformed vs the passphrase was wrong beyond
 *     what the typed error already exposes)
 */
interface BackupEncryptionPort {
    /**
     * Wraps [plaintext] under a key derived from [passphrase]. The returned
     * envelope is a single ASCII string safe to embed in JSON, write to disk,
     * or stream over HTTP.
     */
    fun encrypt(
        plaintext: String,
        passphrase: CharArray,
    ): String

    /**
     * Reverses [encrypt]. Returns [BackupDecryptResult.Success] with the recovered
     * plaintext, or a typed failure on malformed envelope / wrong passphrase /
     * unsupported version.
     */
    fun decrypt(
        envelope: String,
        passphrase: CharArray,
    ): BackupDecryptResult
}

sealed class BackupDecryptResult {
    data class Success(
        val plaintext: String,
    ) : BackupDecryptResult()

    data class Failure(
        val error: BackupDecryptionError,
    ) : BackupDecryptResult()
}

sealed class BackupDecryptionError {
    /** Envelope did not start with `bkp1.` or was structurally malformed. */
    object MalformedEnvelope : BackupDecryptionError()

    /** Envelope version is newer than this build supports. */
    data class UnsupportedVersion(
        val version: String,
    ) : BackupDecryptionError()

    /** GCM tag verification failed — passphrase is wrong or ciphertext was tampered with. */
    object WrongPassphrase : BackupDecryptionError()
}
