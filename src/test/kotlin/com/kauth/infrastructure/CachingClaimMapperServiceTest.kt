package com.kauth.infrastructure

import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.service.AttributeResult
import com.kauth.fakes.FakeTenantClaimMapperRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [CachingClaimMapperService].
 *
 * Covers: read caching, TTL expiry, self-invalidation on write, cross-tenant isolation.
 */
class CachingClaimMapperServiceTest {
    private val repo = FakeTenantClaimMapperRepository()
    private var now = Instant.parse("2026-05-01T12:00:00Z")
    private lateinit var svc: CachingClaimMapperService

    private val tenantA = TenantId(1)
    private val tenantB = TenantId(2)

    @BeforeTest
    fun setup() {
        repo.clear()
        now = Instant.parse("2026-05-01T12:00:00Z")
        svc =
            CachingClaimMapperService(
                mapperRepository = repo,
                ttlMillis = 60_000L,
                clock = { now },
            )
    }

    // =========================================================================
    // caching behavior
    // =========================================================================

    @Test
    fun `first list call populates cache from repository`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        val first = svc.list(tenantA)
        assertEquals(1, first.size)
    }

    @Test
    fun `second list call within TTL does not hit repository`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        svc.list(tenantA) // prime cache

        // Modify the repository directly — bypassing the cache. The cached
        // value should still be returned because TTL has not expired.
        repo.upsert(TenantClaimMapper(tenantA, "trial_ends", "custom:trial_ends"))

        val cached = svc.list(tenantA)
        assertEquals(1, cached.size)
    }

    @Test
    fun `list after TTL expiry refetches from repository`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        svc.list(tenantA) // prime cache

        repo.upsert(TenantClaimMapper(tenantA, "trial_ends", "custom:trial_ends"))
        now = now.plusMillis(60_001L) // advance past TTL

        val refreshed = svc.list(tenantA)
        assertEquals(2, refreshed.size)
    }

    // =========================================================================
    // self-invalidation on write
    // =========================================================================

    @Test
    fun `upsert invalidates the tenant's cache entry`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        svc.list(tenantA) // prime cache

        val result = svc.upsert(TenantClaimMapper(tenantA, "trial_ends", "custom:trial_ends"))
        assertIs<AttributeResult.Success<TenantClaimMapper>>(result)

        // Cache should be invalidated — next read reflects the write immediately.
        val refreshed = svc.list(tenantA)
        assertEquals(2, refreshed.size)
    }

    @Test
    fun `delete invalidates the tenant's cache entry`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        svc.list(tenantA) // prime cache

        val result = svc.delete(tenantA, "plan")
        assertIs<AttributeResult.Success<Unit>>(result)

        val refreshed = svc.list(tenantA)
        assertEquals(0, refreshed.size)
    }

    // =========================================================================
    // cross-tenant isolation
    // =========================================================================

    @Test
    fun `invalidating one tenant does not affect another`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        repo.upsert(TenantClaimMapper(tenantB, "role", "custom:role"))

        svc.list(tenantA)
        svc.list(tenantB)

        // Modify repo for B, then write to A (which invalidates only A).
        repo.upsert(TenantClaimMapper(tenantB, "level", "custom:level"))
        svc.upsert(TenantClaimMapper(tenantA, "tier", "custom:tier"))

        // B should still return cached size (1), A should reflect new write (2).
        assertEquals(1, svc.list(tenantB).size)
        assertEquals(2, svc.list(tenantA).size)
    }

    @Test
    fun `invalidate method clears cache directly`() {
        repo.upsert(TenantClaimMapper(tenantA, "plan", "custom:plan"))
        svc.list(tenantA)

        repo.upsert(TenantClaimMapper(tenantA, "direct", "custom:direct"))
        svc.invalidate(tenantA)

        assertEquals(2, svc.list(tenantA).size)
    }
}
