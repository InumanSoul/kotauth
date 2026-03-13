package com.kauth.domain.model

import java.time.Instant

/**
 * Domain entity representing a user's MFA enrollment.
 *
 * Currently supports TOTP only; [method] is designed to accommodate
 * future methods (webauthn, sms) without schema changes.
 *
 * The [secret] field holds the Base32-encoded TOTP shared secret.
 * It is encrypted at rest via [EncryptionService] in the persistence layer.
 *
 * An enrollment becomes active only after [verified] is true — meaning the
 * user has successfully scanned the QR code and entered a valid OTP.
 */
data class MfaEnrollment(
    val id: Int? = null,
    val userId: Int,
    val tenantId: Int,
    val method: MfaMethod = MfaMethod.TOTP,
    val secret: String,
    val verified: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val verifiedAt: Instant? = null
)

/**
 * Supported MFA methods — extensible without migration.
 */
enum class MfaMethod(val value: String) {
    TOTP("totp");
    // Future: WEBAUTHN("webauthn"), SMS("sms")

    companion object {
        fun fromValue(value: String): MfaMethod =
            entries.firstOrNull { it.value == value } ?: TOTP
    }
}
