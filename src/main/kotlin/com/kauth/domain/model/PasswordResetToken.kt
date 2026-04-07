package com.kauth.domain.model

import java.time.Instant

/**
 * Discriminates the purpose of a [PasswordResetToken].
 *
 * Both purposes share the same table and lifecycle, but differ in expiry
 * (1h for reset, 72h for invite) and in the endpoint that consumes them.
 * Cross-purpose token usage is rejected at the service layer.
 */
enum class TokenPurpose {
    PASSWORD_RESET,
    INVITE,
}

/**
 * Time-limited token used to reset a user's password or accept an invite.
 *
 * More sensitive than [EmailVerificationToken] — valid for only 1 hour (reset)
 * or 72 hours (invite). On successful use, all active sessions for the user
 * are revoked (reset) or the password is set and email verified (invite).
 *
 * Lifecycle (reset):
 *   1. User submits email → token generated → email sent with reset link.
 *   2. User clicks link → raw token hashed → matched against DB.
 *   3. User submits new password → token marked used → password updated
 *      → all existing sessions revoked.
 *
 * Lifecycle (invite):
 *   1. Admin creates user with invite → token generated → email sent.
 *   2. User clicks link → raw token hashed → matched against DB.
 *   3. User sets password → token marked used → email verified
 *      → requiredActions cleared.
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
    val purpose: TokenPurpose = TokenPurpose.PASSWORD_RESET,
    val usedAt: Instant? = null,
    val ipAddress: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isUsed: Boolean get() = usedAt != null
    val isValid: Boolean get() = !isExpired && !isUsed
}
