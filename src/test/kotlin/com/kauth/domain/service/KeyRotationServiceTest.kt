package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyRotationServiceTest {
    private val tenants = FakeTenantRepository()
    private val keys = FakeTenantKeyRepository()
    private val tokens = FakeTokenPort()
    private val auditLog = FakeAuditLogPort()

    private val svc =
        KeyRotationService(
            tenantKeyRepository = keys,
            tenantRepository = tenants,
            tokenPort = tokens,
            auditLog = auditLog,
        )

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private val activeKey =
        TenantKey(
            tenantId = TenantId(1),
            keyId = "acme-original",
            publicKeyPem = "fake-pub",
            privateKeyPem = "fake-priv",
            enabled = true,
            active = true,
        )

    @BeforeTest
    fun setup() {
        tenants.clear()
        keys.clear()
        auditLog.clear()
        tokens.reset()
        tenants.add(tenant)
        keys.add(activeKey)
    }

    // =========================================================================
    // rotate
    // =========================================================================

    @Test
    fun `rotate succeeds and returns new key`() {
        val result = svc.rotate(TenantId(1), UserId(1))
        assertIs<AdminResult.Success<TenantKey>>(result)
        assertTrue(result.value.active)
        assertTrue(result.value.keyId.startsWith("acme-"))
        assertTrue(result.value.keyId != "acme-original")
    }

    @Test
    fun `rotate demotes old key to verification only`() {
        svc.rotate(TenantId(1), UserId(1))
        val oldKey = keys.findByKeyId(TenantId(1), "acme-original")
        assertNotNull(oldKey)
        assertTrue(oldKey.enabled, "Old key should remain enabled for JWKS")
        assertFalse(oldKey.active, "Old key should no longer be the active signing key")
    }

    @Test
    fun `rotate invalidates signing key cache`() {
        svc.rotate(TenantId(1), UserId(1))
        assertEquals(TenantId(1), tokens.cacheInvalidatedForTenant)
    }

    @Test
    fun `rotate records ADMIN_KEY_ROTATED audit event`() {
        svc.rotate(TenantId(1), UserId(1))
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_KEY_ROTATED))
    }

    @Test
    fun `rotate fails when tenant not found`() {
        val result = svc.rotate(TenantId(999), UserId(1))
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `rotate fails when no active key exists`() {
        keys.clear()
        keys.add(activeKey.copy(active = false))
        val result = svc.rotate(TenantId(1), UserId(1))
        assertIs<AdminResult.Failure>(result)
    }

    // =========================================================================
    // retireKey
    // =========================================================================

    @Test
    fun `retireKey succeeds for non-active enabled key`() {
        // First rotate so we have an old key to retire
        svc.rotate(TenantId(1), UserId(1))
        val result = svc.retireKey(TenantId(1), "acme-original", UserId(1))
        assertIs<AdminResult.Success<Unit>>(result)
        val retired = keys.findByKeyId(TenantId(1), "acme-original")
        assertNotNull(retired)
        assertFalse(retired.enabled, "Retired key should be disabled")
    }

    @Test
    fun `retireKey fails for active key`() {
        val result = svc.retireKey(TenantId(1), "acme-original", UserId(1))
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `retireKey fails for unknown key`() {
        val result = svc.retireKey(TenantId(1), "nonexistent", UserId(1))
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `retireKey records ADMIN_KEY_RETIRED audit event`() {
        svc.rotate(TenantId(1), UserId(1))
        svc.retireKey(TenantId(1), "acme-original", UserId(1))
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_KEY_RETIRED))
    }
}
