package com.kauth.domain.port

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId

/**
 * Port (outbound) — tenant-level claim mapping configuration.
 *
 * Mappers project user attributes into issued JWTs. The list is small
 * (bounded by [TenantClaimMapper.MAX_MAPPERS_PER_TENANT]) and read on every
 * token issuance, so [findAll] is cached at a higher layer.
 */
interface TenantClaimMapperRepository {
    /** Returns every mapper configured for the tenant. Empty if none. */
    fun findAll(tenantId: TenantId): List<TenantClaimMapper>

    /** Upserts a single mapper. Creates on new attribute key, overwrites on match. */
    fun upsert(mapper: TenantClaimMapper)

    /** Deletes a mapper by its natural key. Returns true if a row was removed. */
    fun delete(
        tenantId: TenantId,
        attributeKey: String,
    ): Boolean
}

/**
 * Signals cache invalidation for a tenant's mappers. Implemented by the
 * caching decorator in the infrastructure layer; invoked by the write path
 * ([com.kauth.domain.service.ClaimMapperService]) after every mutating call.
 */
fun interface ClaimMapperCacheInvalidator {
    fun invalidate(tenantId: TenantId)

    companion object {
        /** No-op invalidator for contexts without a cache (tests, startup). */
        val NOOP = ClaimMapperCacheInvalidator { _ -> }
    }
}
