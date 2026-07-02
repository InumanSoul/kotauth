package com.kauth.fakes

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeTenantRepositoryTest {
    private val repo = FakeTenantRepository()

    @BeforeTest
    fun reset() = repo.clear()

    @Test
    fun `passkeysEnabled defaults to true and round-trips on update`() {
        val tenant = repo.create(slug = "acme", displayName = "Acme", issuerUrl = null)
        assertEquals(true, tenant.passkeysEnabled)

        repo.update(tenant.copy(passkeysEnabled = false))
        assertEquals(false, repo.findBySlug("acme")?.passkeysEnabled)
    }
}
