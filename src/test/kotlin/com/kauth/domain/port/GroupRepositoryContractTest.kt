package com.kauth.domain.port

import com.kauth.domain.model.Group
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeGroupRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the [GroupRepository.findByTenantId] `loadRoles` contract: the flag decides whether
 * [Group.roleIds] is loaded at all, so an empty list under `loadRoles = false` means "not
 * loaded" and never "this group has no roles".
 */
class GroupRepositoryContractTest {
    private val tenant = TenantId(1)
    private val repo = FakeGroupRepository()

    @BeforeTest
    fun setup() {
        repo.clear()
    }

    @Test
    fun `loadRoles false returns empty roleIds for a group that genuinely has roles`() {
        val group = repo.save(Group(tenantId = tenant, name = "engineering"))
        repo.assignRoleToGroup(group.id!!, RoleId(7))
        repo.assignRoleToGroup(group.id, RoleId(8))

        val loaded = repo.findByTenantId(tenant, loadRoles = true).single()
        assertEquals(setOf(RoleId(7), RoleId(8)), loaded.roleIds.toSet())

        val skipped = repo.findByTenantId(tenant, loadRoles = false).single()
        assertEquals(emptyList(), skipped.roleIds)
        assertEquals(loaded.id, skipped.id)
        assertEquals(loaded.name, skipped.name)
    }

    @Test
    fun `loadRoles defaults to true so an unaware caller still sees roles`() {
        val group = repo.save(Group(tenantId = tenant, name = "engineering"))
        repo.assignRoleToGroup(group.id!!, RoleId(7))

        assertEquals(listOf(RoleId(7)), repo.findByTenantId(tenant).single().roleIds)
    }
}
