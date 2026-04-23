package com.kauth.domain.model

import java.time.Instant

/**
 * Per-user key/value metadata. Values are always strings — callers serialize
 * structured data themselves. Attributes are opaque to KotAuth; there is no
 * reserved-key list or type validation.
 *
 * See [TenantClaimMapper] for projecting attributes into JWT claims.
 */
data class UserAttribute(
    val userId: UserId,
    val tenantId: TenantId,
    val key: String,
    val value: String,
    val updatedAt: Instant,
) {
    companion object {
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 1024
    }
}
