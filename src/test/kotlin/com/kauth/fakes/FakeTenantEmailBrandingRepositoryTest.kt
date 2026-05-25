package com.kauth.fakes

import com.kauth.domain.model.TenantEmailBranding
import com.kauth.domain.model.TenantId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FakeTenantEmailBrandingRepositoryTest {
    private val repo = FakeTenantEmailBrandingRepository()
    private val tenantId = TenantId(7)

    @BeforeTest
    fun reset() = repo.clear()

    @Test
    fun `findByTenantId returns null when not set`() {
        assertNull(repo.findByTenantId(tenantId))
    }

    @Test
    fun `upsert inserts when missing and assigns an id`() {
        val saved = repo.upsert(TenantEmailBranding(tenantId = tenantId, brandName = "Acme"))
        assertNotNull(saved.id)
        assertEquals("Acme", repo.findByTenantId(tenantId)?.brandName)
    }

    @Test
    fun `upsert updates fields while keeping the same id`() {
        val first = repo.upsert(TenantEmailBranding(tenantId = tenantId, brandName = "Acme"))
        val second = repo.upsert(TenantEmailBranding(tenantId = tenantId, brandName = "Acme Renamed"))
        assertEquals(first.id, second.id)
        assertEquals("Acme Renamed", repo.findByTenantId(tenantId)?.brandName)
    }

    @Test
    fun `deleteByTenantId removes the row`() {
        repo.upsert(TenantEmailBranding(tenantId = tenantId, brandName = "Acme"))
        repo.deleteByTenantId(tenantId)
        assertNull(repo.findByTenantId(tenantId))
    }
}
