package com.kauth.domain.service

import com.kauth.domain.model.ClaimTokenType
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ClaimMapperCacheInvalidator
import com.kauth.fakes.FakeTenantClaimMapperRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaimMapperServiceTest {
    private val mappers = FakeTenantClaimMapperRepository()

    private val invalidations = mutableListOf<TenantId>()
    private val invalidator = ClaimMapperCacheInvalidator { tid -> invalidations.add(tid) }

    private val svc = ClaimMapperService(mapperRepository = mappers, cacheInvalidator = invalidator)

    private val tenantId = TenantId(1)

    @BeforeTest
    fun setup() {
        mappers.clear()
        invalidations.clear()
    }

    // =========================================================================
    // upsert
    // =========================================================================

    @Test
    fun `upsert - creates new mapper`() {
        val mapper =
            TenantClaimMapper(
                tenantId = tenantId,
                attributeKey = "plan",
                claimName = "custom:plan",
            )
        val result = svc.upsert(mapper)
        assertIs<AttributeResult.Success<TenantClaimMapper>>(result)
        assertEquals(listOf(mapper), mappers.findAll(tenantId))
    }

    @Test
    fun `upsert - overwrites existing mapper`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:tier", includeInId = true))

        val stored = mappers.findAll(tenantId).single { it.attributeKey == "plan" }
        assertEquals("custom:tier", stored.claimName)
        assertTrue(stored.includeInId)
    }

    @Test
    fun `upsert - blank attribute key rejected`() {
        val result = svc.upsert(TenantClaimMapper(tenantId, "  ", "custom:plan"))
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - blank claim name rejected`() {
        val result = svc.upsert(TenantClaimMapper(tenantId, "plan", "  "))
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - claim name too long rejected`() {
        val tooLong = "c".repeat(TenantClaimMapper.MAX_CLAIM_NAME_LENGTH + 1)
        val result = svc.upsert(TenantClaimMapper(tenantId, "plan", tooLong))
        assertIs<AttributeResult.ValidationError>(result)
    }

    @Test
    fun `upsert - reserved OIDC claim names are blocked`() {
        val reserved = listOf("sub", "iss", "aud", "exp", "iat", "nonce", "email", "tenant_id", "scope")
        reserved.forEach { claimName ->
            val result = svc.upsert(TenantClaimMapper(tenantId, "attr_$claimName", claimName))
            assertIs<AttributeResult.ReservedClaimName>(result)
            assertEquals(claimName, result.claimName)
        }
    }

    @Test
    fun `upsert - duplicate claim name across attribute keys rejected`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:tier"))
        val result = svc.upsert(TenantClaimMapper(tenantId, "subscription", "custom:tier"))
        assertIs<AttributeResult.DuplicateClaimName>(result)
    }

    @Test
    fun `upsert - same attribute key rewriting same claim name allowed`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        val result = svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan", includeInId = true))
        assertIs<AttributeResult.Success<TenantClaimMapper>>(result)
    }

    @Test
    fun `upsert - enforces MAX_MAPPERS_PER_TENANT`() {
        repeat(TenantClaimMapper.MAX_MAPPERS_PER_TENANT) { i ->
            svc.upsert(TenantClaimMapper(tenantId, "attr_$i", "custom:$i"))
        }
        val result = svc.upsert(TenantClaimMapper(tenantId, "attr_overflow", "custom:overflow"))
        assertIs<AttributeResult.LimitReached>(result)
        assertEquals(TenantClaimMapper.MAX_MAPPERS_PER_TENANT, result.max)
    }

    @Test
    fun `upsert - editing existing mapper does not count against cap`() {
        repeat(TenantClaimMapper.MAX_MAPPERS_PER_TENANT) { i ->
            svc.upsert(TenantClaimMapper(tenantId, "attr_$i", "custom:$i"))
        }
        val result = svc.upsert(TenantClaimMapper(tenantId, "attr_0", "custom:0", includeInId = true))
        assertIs<AttributeResult.Success<TenantClaimMapper>>(result)
    }

    @Test
    fun `upsert - triggers cache invalidation`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        assertEquals(listOf(tenantId), invalidations)
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Test
    fun `delete - removes existing mapper`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        invalidations.clear()

        val result = svc.delete(tenantId, "plan")
        assertIs<AttributeResult.Success<Unit>>(result)
        assertTrue(mappers.findAll(tenantId).isEmpty())
        assertEquals(listOf(tenantId), invalidations)
    }

    @Test
    fun `delete - missing mapper returns Success`() {
        val result = svc.delete(tenantId, "nonexistent")
        assertIs<AttributeResult.Success<Unit>>(result)
    }

    // =========================================================================
    // list
    // =========================================================================

    @Test
    fun `list - returns mappers for tenant`() {
        svc.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        svc.upsert(TenantClaimMapper(tenantId, "trial_ends", "custom:trial_ends"))
        svc.upsert(TenantClaimMapper(TenantId(2), "plan", "custom:plan"))

        val result = svc.list(tenantId)
        assertEquals(2, result.size)
        assertTrue(result.all { it.tenantId == tenantId })
    }

    // =========================================================================
    // projectClaims — pure function
    // =========================================================================

    @Test
    fun `projectClaims - injects matching attributes for access token`() {
        val mapperList =
            listOf(
                TenantClaimMapper(tenantId, "plan", "custom:plan", includeInAccess = true, includeInId = true),
                TenantClaimMapper(
                    tenantId,
                    "trial_ends",
                    "custom:trial_ends",
                    includeInAccess = true,
                    includeInId = false,
                ),
            )
        val attrs = mapOf("plan" to "trial", "trial_ends" to "2026-05-21")

        val result = ClaimMapperService.projectClaims(mapperList, attrs, ClaimTokenType.ACCESS)
        assertEquals(
            mapOf("custom:plan" to "trial", "custom:trial_ends" to "2026-05-21"),
            result,
        )
    }

    @Test
    fun `projectClaims - filters by includeInId flag for id token`() {
        val mapperList =
            listOf(
                TenantClaimMapper(tenantId, "plan", "custom:plan", includeInAccess = true, includeInId = true),
                TenantClaimMapper(
                    tenantId,
                    "trial_ends",
                    "custom:trial_ends",
                    includeInAccess = true,
                    includeInId = false,
                ),
            )
        val attrs = mapOf("plan" to "trial", "trial_ends" to "2026-05-21")

        val result = ClaimMapperService.projectClaims(mapperList, attrs, ClaimTokenType.ID)
        assertEquals(mapOf("custom:plan" to "trial"), result)
    }

    @Test
    fun `projectClaims - attribute missing skips the claim`() {
        val mapperList =
            listOf(
                TenantClaimMapper(tenantId, "plan", "custom:plan"),
                TenantClaimMapper(tenantId, "missing", "custom:missing"),
            )
        val attrs = mapOf("plan" to "trial")

        val result = ClaimMapperService.projectClaims(mapperList, attrs, ClaimTokenType.ACCESS)
        assertEquals(mapOf("custom:plan" to "trial"), result)
    }

    @Test
    fun `projectClaims - no mappers returns empty map`() {
        val result = ClaimMapperService.projectClaims(emptyList(), mapOf("plan" to "trial"), ClaimTokenType.ACCESS)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `projectClaims - no attributes returns empty map`() {
        val mapperList = listOf(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        val result = ClaimMapperService.projectClaims(mapperList, emptyMap(), ClaimTokenType.ACCESS)
        assertTrue(result.isEmpty())
    }
}
