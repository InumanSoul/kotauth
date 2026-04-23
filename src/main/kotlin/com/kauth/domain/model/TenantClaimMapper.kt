package com.kauth.domain.model

/**
 * Tenant-level rule that projects a user attribute into issued JWTs.
 * Identity is the composite (tenantId, attributeKey).
 *
 * Claim name collision with standard OIDC claims (sub, iss, aud, etc.) is
 * rejected at the service layer — see [com.kauth.domain.service.ClaimMapperService].
 */
data class TenantClaimMapper(
    val tenantId: TenantId,
    val attributeKey: String,
    val claimName: String,
    val includeInAccess: Boolean = true,
    val includeInId: Boolean = false,
) {
    companion object {
        const val MAX_CLAIM_NAME_LENGTH = 128

        /** Soft cap to keep JWTs from bloating. Enforced at write time. */
        const val MAX_MAPPERS_PER_TENANT = 20
    }
}

/** Which token type a claim projection is being built for. */
enum class ClaimTokenType { ACCESS, ID }
