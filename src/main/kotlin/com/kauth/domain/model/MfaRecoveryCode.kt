package com.kauth.domain.model

import java.time.Instant

/**
 * A one-time MFA recovery code.
 *
 * Generated during TOTP enrollment — typically 8–10 codes per user.
 * Each code is BCrypt-hashed before storage; the plaintext is shown
 * to the user exactly once during enrollment and never stored.
 *
 * [usedAt] is non-null once consumed — the code cannot be reused.
 */
data class MfaRecoveryCode(
    val id: Int? = null,
    val userId: UserId,
    val tenantId: TenantId,
    val codeHash: String,
    val usedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
