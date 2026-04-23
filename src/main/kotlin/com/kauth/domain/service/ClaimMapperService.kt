package com.kauth.domain.service

import com.kauth.domain.model.ClaimTokenType
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ClaimMapperCacheInvalidator
import com.kauth.domain.port.TenantClaimMapperRepository

/**
 * Domain service — tenant-level claim-mapper configuration and projection.
 *
 * Reserved claim names are blocked at write time to prevent admins from
 * stomping on standard OIDC claims or KotAuth-proprietary claims.
 *
 * [projectClaims] is a pure function: given a mapper list, an attribute map,
 * and a token type, it returns the claims to inject into that token. It
 * contains no I/O and is safe to call on the hot token-issuance path.
 *
 * Write operations invalidate the tenant's cache entry via the injected
 * [ClaimMapperCacheInvalidator]. Reads go through the caching layer
 * ([com.kauth.infrastructure.CachingClaimMapperService]) in production.
 */
class ClaimMapperService(
    private val mapperRepository: TenantClaimMapperRepository,
    private val cacheInvalidator: ClaimMapperCacheInvalidator = ClaimMapperCacheInvalidator.NOOP,
) {
    fun list(tenantId: TenantId): List<TenantClaimMapper> = mapperRepository.findAll(tenantId)

    fun upsert(mapper: TenantClaimMapper): AttributeResult<TenantClaimMapper> {
        if (mapper.attributeKey.isBlank()) {
            return AttributeResult.ValidationError("Attribute key must not be blank.")
        }
        if (mapper.claimName.isBlank()) {
            return AttributeResult.ValidationError("Claim name must not be blank.")
        }
        if (mapper.claimName.length > TenantClaimMapper.MAX_CLAIM_NAME_LENGTH) {
            return AttributeResult.ValidationError(
                "Claim name must be at most ${TenantClaimMapper.MAX_CLAIM_NAME_LENGTH} characters.",
            )
        }
        if (mapper.claimName in RESERVED_CLAIMS) {
            return AttributeResult.ReservedClaimName(mapper.claimName)
        }

        val existing = mapperRepository.findAll(mapper.tenantId)

        // Enforce per-tenant cap, but only for new mappers (edits don't count).
        val isNew = existing.none { it.attributeKey == mapper.attributeKey }
        if (isNew && existing.size >= TenantClaimMapper.MAX_MAPPERS_PER_TENANT) {
            return AttributeResult.LimitReached(TenantClaimMapper.MAX_MAPPERS_PER_TENANT)
        }

        // Reject two attribute keys projecting to the same claim name.
        val claimNameClash =
            existing.any { it.claimName == mapper.claimName && it.attributeKey != mapper.attributeKey }
        if (claimNameClash) {
            return AttributeResult.DuplicateClaimName(mapper.claimName)
        }

        mapperRepository.upsert(mapper)
        cacheInvalidator.invalidate(mapper.tenantId)
        return AttributeResult.Success(mapper)
    }

    fun delete(
        tenantId: TenantId,
        attributeKey: String,
    ): AttributeResult<Unit> {
        mapperRepository.delete(tenantId, attributeKey)
        cacheInvalidator.invalidate(tenantId)
        return AttributeResult.Success(Unit)
    }

    companion object {
        /**
         * Claim names that callers may not use as custom claim targets.
         * Covers standard OIDC/JWT claims and KotAuth-proprietary claims
         * that downstream consumers rely on.
         */
        val RESERVED_CLAIMS =
            setOf(
                // RFC 7519 + OIDC core
                "sub",
                "iss",
                "aud",
                "exp",
                "iat",
                "nbf",
                "jti",
                "nonce",
                "auth_time",
                "acr",
                "amr",
                "azp",
                // OIDC standard profile
                "email",
                "email_verified",
                "name",
                "preferred_username",
                "given_name",
                "family_name",
                "middle_name",
                "nickname",
                "profile",
                "picture",
                "website",
                "gender",
                "birthdate",
                "zoneinfo",
                "locale",
                "phone_number",
                "phone_number_verified",
                "address",
                "updated_at",
                // KotAuth proprietary (tokens consumers parse)
                "tenant_id",
                "username",
                "scope",
                "client_id",
                "realm_access",
                "resource_access",
            )

        /**
         * Pure projection: for a given mapper list and attribute map, returns
         * the claim-name→value entries to add to a token of [tokenType].
         * Attributes missing from the user's set are silently skipped.
         */
        fun projectClaims(
            mappers: List<TenantClaimMapper>,
            attributes: Map<String, String>,
            tokenType: ClaimTokenType,
        ): Map<String, String> =
            mappers
                .filter { if (tokenType == ClaimTokenType.ACCESS) it.includeInAccess else it.includeInId }
                .mapNotNull { mapper -> attributes[mapper.attributeKey]?.let { mapper.claimName to it } }
                .toMap()
    }
}
