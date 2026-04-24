package com.kauth.infrastructure

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ClaimMapperCacheInvalidator
import com.kauth.domain.port.TenantClaimMapperRepository
import com.kauth.domain.service.AttributeResult
import com.kauth.domain.service.ClaimMapperService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Caching decorator around [ClaimMapperService] for the hot token-issuance path.
 *
 * Reads ([list]) are served from an in-memory cache with a [ttlMillis] TTL.
 * Writes go through the wrapped [ClaimMapperService], which invokes this class's
 * invalidator to drop the cached entry. The wiring is self-referential: the
 * underlying service is constructed with `this::invalidate` as its invalidator.
 */
class CachingClaimMapperService(
    mapperRepository: TenantClaimMapperRepository,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Instant = Instant::now,
) : ClaimMapperCacheInvalidator {
    private data class CachedMappers(
        val mappers: List<TenantClaimMapper>,
        val expireAt: Instant,
    )

    private val cache = ConcurrentHashMap<Int, CachedMappers>()

    private val delegate: ClaimMapperService = ClaimMapperService(mapperRepository, this)

    fun list(tenantId: TenantId): List<TenantClaimMapper> {
        val now = clock()
        val cached = cache[tenantId.value]
        if (cached != null && cached.expireAt.isAfter(now)) {
            return cached.mappers
        }
        val fresh = delegate.list(tenantId)
        cache[tenantId.value] = CachedMappers(fresh, now.plusMillis(ttlMillis))
        return fresh
    }

    fun upsert(mapper: TenantClaimMapper): AttributeResult<TenantClaimMapper> = delegate.upsert(mapper)

    fun delete(
        tenantId: TenantId,
        attributeKey: String,
    ): AttributeResult<Unit> = delegate.delete(tenantId, attributeKey)

    override fun invalidate(tenantId: TenantId) {
        cache.remove(tenantId.value)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 60_000L
    }
}
