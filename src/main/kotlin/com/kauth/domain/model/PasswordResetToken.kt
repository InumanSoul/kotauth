package com.kauth.domain.model

import java.time.Instant

/**
 * Time-limited token used to reset a user's password.
 *
 * More sensitive than [EmailVerificationToken] — valid for only 1 hour.
 * On successful use, all active sessions for the user are revoked.
 *
 * Lifecycle:
 *   1. User submits email → token generated → email sent with reset link.
 *   2. User clicks link → raw token hashed → matched against DB.
 *   3. User submits new password → token marked used → password updated
 *      → all existing sessions revoked.
 *
 * SECURITY: The forgot-password flow always returns success regardless of
 * whether the email exists. This prevents user enumeration.
 */
data class PasswordResetToken(
    val id: Int? = null,
    val userId: UserId,
    val tenantId: TenantId,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val ipAddress: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isUsed: Boolean get() = usedAt != null
    val isValid: Boolean get() = !isExpired && !isUsed
}
