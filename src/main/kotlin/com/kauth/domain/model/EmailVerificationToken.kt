package com.kauth.domain.model

import java.time.Instant

/**
 * Time-limited token used to verify a user's email address.
 *
 * The raw token lives only in the verification email link — only its SHA-256 hash
 * is persisted in [tokenHash]. Verification is valid for 24 hours.
 *
 * Lifecycle:
 *   1. Generated → email sent with link containing raw token.
 *   2. User clicks link → raw token is hashed → matched against DB.
 *   3. Token marked used ([usedAt] set) → user.emailVerified = true.
 *
 * Previous unused tokens for the same user are deleted before issuing a new one.
 */
data class EmailVerificationToken(
    val id: Int?           = null,
    val userId: Int,
    val tenantId: Int,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant?   = null,
    val createdAt: Instant = Instant.now()
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isUsed: Boolean    get() = usedAt != null
    val isValid: Boolean   get() = !isExpired && !isUsed
}
