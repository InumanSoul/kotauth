package com.kauth.domain.model

import java.time.Instant

/**
 * Short-lived email OTP challenge. The raw 6-digit code is never stored —
 * only its SHA-256 in [codeHash]. [challengeId] is the public opaque handle;
 * [id] stays internal.
 */
data class EmailOtpChallenge(
    val id: Int? = null,
    val userId: UserId,
    val tenantId: TenantId,
    val challengeId: String,
    val codeHash: String,
    val originatingClientId: String? = null,
    val resources: List<String> = emptyList(),
    val attemptCount: Int = 0,
    val resendCount: Int = 0,
    val expiresAt: Instant,
    val consumedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isConsumed: Boolean get() = consumedAt != null
    val isValid: Boolean get() = !isExpired && !isConsumed
}
