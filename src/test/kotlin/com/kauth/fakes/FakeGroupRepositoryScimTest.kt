package com.kauth.fakes

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `findGroupsForUsers batches lookups for multiple users into one map`() {
        val repo = FakeGroupRepository()
        val engineering = repo.save(Group(tenantId = tenantA, name = "engineering"))
        val sales = repo.save(Group(tenantId = tenantA, name = "sales"))
        val alice = UserId(1)
        val bob = UserId(2)
        val carol = UserId(3)
        repo.addUserToGroup(alice, engineering.id!!)
        repo.addUserToGroup(bob, sales.id!!)

        val result = repo.findGroupsForUsers(listOf(alice, bob, carol))

        assertEquals(listOf("engineering"), result[alice]?.map { it.name })
        assertEquals(listOf("sales"), result[bob]?.map { it.name })
        // A user with no memberships has no entry, matching the single-user lookup's semantics.
        assertTrue(carol !in result)
    }

    @Test
    fun `findGroupsForUsers on an empty id list returns an empty map`() {
        val repo = FakeGroupRepository()

        val result = repo.findGroupsForUsers(emptyList())

        assertEquals(emptyMap(), result)
    }
}
