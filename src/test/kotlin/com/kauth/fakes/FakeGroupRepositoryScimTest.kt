package com.kauth.fakes

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FakeGroupRepositoryScimTest {
    private val tenantA = TenantId(1)
    private val tenantB = TenantId(2)

    private fun group(
        name: String,
        tenant: TenantId,
        ext: String?,
    ) = Group(
        id = GroupId(0),
        tenantId = tenant,
        name = name,
        externalId = ext,
    )

    @Test
    fun `findByExternalId returns the matching group`() {
        val repo = FakeGroupRepository()
        repo.save(group("engineering", tenantA, "ext-1"))

        assertEquals("engineering", repo.findByExternalId(tenantA, "ext-1")?.name)
    }

    @Test
    fun `findByExternalId is scoped to the tenant`() {
        val repo = FakeGroupRepository()
        repo.save(group("engineering", tenantA, "ext-1"))

        // Same external id in a different workspace must not leak across.
        assertNull(repo.findByExternalId(tenantB, "ext-1"))
    }

    @Test
    fun `findByExternalId ignores groups that have no external id`() {
        val repo = FakeGroupRepository()
        repo.save(group("local-group", tenantA, null))

        assertNull(repo.findByExternalId(tenantA, "ext-1"))
    }
}
