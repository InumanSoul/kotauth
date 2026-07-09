package com.kauth.fakes

import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeResourceServerRepositoryTest {
    private val tenantId = TenantId(1)
    private val repo = FakeResourceServerRepository()

    @BeforeTest
    fun reset() = repo.clear()

    @Test
    fun `scopes round-trip through seed and findByIdentifier`() {
        repo.seed(
            ResourceServer(
                id = ResourceServerId(1),
                tenantId = tenantId,
                identifier = "https://api.example.com",
                name = "Example API",
                description = null,
                enabled = true,
                scopes = listOf("read:invoices", "write:invoices"),
                createdAt = Instant.now(),
            ),
        )
        val fetched = repo.findByIdentifier(tenantId, "https://api.example.com")
        assertEquals(listOf("read:invoices", "write:invoices"), fetched?.scopes)
        assertEquals(ResourceServerId(1), fetched?.id)
    }
}
