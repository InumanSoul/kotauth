package com.kauth.fakes

import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import com.kauth.domain.port.BackupEncryptionPort

/**
 * Test fake — wraps plaintext into a sentinel envelope that records the
 * passphrase so decrypt can verify it. No real cryptography.
 *
 * Envelope shape: `fakebkp1.<passphrase>.<base64plaintext>`
 *
 * Decrypt fails with [BackupDecryptionError.WrongPassphrase] when the supplied
 * passphrase doesn't match the one captured at encrypt time — that's enough to
 * exercise the wrong-passphrase code path in importer tests without depending
 * on PBKDF2's per-call cost.
 */
class FakeBackupEncryptionPort : BackupEncryptionPort {
    var lastPassphrase: String? = null
        private set

    override fun encrypt(
        plaintext: String,
        passphrase: CharArray,
    ): String {
        val pass = String(passphrase)
        lastPassphrase = pass
        val payload =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                plaintext.toByteArray(Charsets.UTF_8),
            )
        return "$PREFIX$pass.$payload"
    }

    override fun decrypt(
        envelope: String,
        passphrase: CharArray,
    ): BackupDecryptResult {
        if (!envelope.startsWith(PREFIX)) {
            return BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
        }
        val rest = envelope.removePrefix(PREFIX)
        val sepIdx = rest.indexOf('.')
        if (sepIdx < 0) return BackupDecryptResult.Failure(BackupDecryptionError.MalformedEnvelope)
        val capturedPass = rest.substring(0, sepIdx)
        if (capturedPass != String(passphrase)) {
            return BackupDecryptResult.Failure(BackupDecryptionError.WrongPassphrase)
        }
        val payload = rest.substring(sepIdx + 1)
        val plaintext =
            String(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(payload),
                Charsets.UTF_8,
            )
        return BackupDecryptResult.Success(plaintext)
    }

    companion object {
        const val PREFIX = "fakebkp1."
    }
}
