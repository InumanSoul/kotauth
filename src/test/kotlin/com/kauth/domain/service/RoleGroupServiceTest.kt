package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [RoleGroupService].
 *
 * Covers: role CRUD, composite roles (cycle detection), user-role assignment,
 * group CRUD, group-role assignment, user-group membership.
 */
class RoleGroupServiceTest {
    private val roles = FakeRoleRepository()
    private val groups = FakeGroupRepository()
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

    private val svc =
        RoleGroupService(
            roleRepository = roles,
            groupRepository = groups,
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            auditLog = auditLog,
        )

    private val tenant =
        Tenant(id = 1, slug = "acme", displayName = "Acme Corp", issuerUrl = null)

    private val testApp =
        Application(
            id = 100, tenantId = 1, clientId = "my-app", name = "My App",
            description = null, accessType = AccessType.CONFIDENTIAL, enabled = true,
        )

    private val alice
        get() = User(
            id = 10, tenantId = 1, username = "alice", email = "alice@example.com",
            fullName = "Alice", passwordHash = hasher.hash("pass"),
        )

    private val bob
        get() = User(
            id = 11, tenantId = 1, username = "bob", email = "bob@example.com",
            fullName = "Bob", passwordHash = hasher.hash("pass"),
        )

    @BeforeTest
    fun setup() {
        roles.clear()
        groups.clear()
        tenants.clear()
        users.clear()
        apps.clear()
        auditLog.clear()
        tenants.add(tenant)
        apps.add(testApp)
        users.add(alice)
        users.add(bob)
    }

    // =========================================================================
    // createRole
    // =========================================================================

    @Test
    fun `createRole - blank name`() {
        val result = svc.createRole(tenantId = 1, name = "  ", description = null, scope = RoleScope.TENANT, clientId = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createRole - invalid name characters`() {
        val result = svc.createRole(tenantId = 1, name = "bad role!", description = null, scope = RoleScope.TENANT, clientId = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createRole - client scope without clientId`() {
        val result = svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.CLIENT, clientId = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createRole - tenant scope with clientId`() {
        val result = svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.TENANT, clientId = 100)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createRole - client scope with unknown app`() {
        val result = svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.CLIENT, clientId = 999)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `createRole - duplicate name`() {
        svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.TENANT, clientId = null)
        val result = svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.TENANT, clientId = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createRole - same name different scopes is allowed`() {
        svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.TENANT, clientId = null)
        val result = svc.createRole(tenantId = 1, name = "admin", description = null, scope = RoleScope.CLIENT, clientId = 100)
        assertIs<AdminResult.Success<Role>>(result)
    }

    @Test
    fun `createRole - success tenant scoped`() {
        val result = svc.createRole(tenantId = 1, name = "admin", description = "Admin role", scope = RoleScope.TENANT, clientId = null)
        assertIs<AdminResult.Success<Role>>(result)
        assertEquals("admin", result.value.name)
        assertEquals(RoleScope.TENANT, result.value.scope)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_ROLE_CREATED))
    }

    @Test
    fun `createRole - success client scoped`() {
        val result = svc.createRole(tenantId = 1, name = "editor", description = null, scope = RoleScope.CLIENT, clientId = 100)
        assertIs<AdminResult.Success<Role>>(result)
        assertEquals(100, result.value.clientId)
    }

    // =========================================================================
    // updateRole
    // =========================================================================

    @Test
    fun `updateRole - not found`() {
        val result = svc.updateRole(roleId = 999, tenantId = 1, name = "X", description = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateRole - tenant mismatch`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.updateRole(roleId = role.id!!, tenantId = 99, name = "X", description = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateRole - name conflict`() {
        svc.createRole(1, "admin", null, RoleScope.TENANT, null)
        val editor = (svc.createRole(1, "editor", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.updateRole(roleId = editor.id!!, tenantId = 1, name = "admin", description = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `updateRole - success`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.updateRole(roleId = role.id!!, tenantId = 1, name = "super-admin", description = "Elevated")
        assertIs<AdminResult.Success<Role>>(result)
        assertEquals("super-admin", result.value.name)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_ROLE_UPDATED))
    }

    // =========================================================================
    // deleteRole
    // =========================================================================

    @Test
    fun `deleteRole - not found`() {
        val result = svc.deleteRole(roleId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `deleteRole - success`() {
        val role = (svc.createRole(1, "temp", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.deleteRole(role.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_ROLE_DELETED))
    }

    // =========================================================================
    // listRoles / listClientRoles
    // =========================================================================

    @Test
    fun `listRoles - returns all roles for tenant`() {
        svc.createRole(1, "admin", null, RoleScope.TENANT, null)
        svc.createRole(1, "editor", null, RoleScope.CLIENT, 100)
        assertEquals(2, svc.listRoles(1).size)
        assertEquals(0, svc.listRoles(99).size)
    }

    @Test
    fun `listClientRoles - returns only client-scoped roles`() {
        svc.createRole(1, "admin", null, RoleScope.TENANT, null)
        svc.createRole(1, "editor", null, RoleScope.CLIENT, 100)
        assertEquals(1, svc.listClientRoles(1, 100).size)
    }

    // =========================================================================
    // Composite roles (addChildRole / removeChildRole)
    // =========================================================================

    @Test
    fun `addChildRole - parent not found`() {
        val child = (svc.createRole(1, "child", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.addChildRole(parentRoleId = 999, childRoleId = child.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `addChildRole - child not found`() {
        val parent = (svc.createRole(1, "parent", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.addChildRole(parentRoleId = parent.id!!, childRoleId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `addChildRole - self reference`() {
        val role = (svc.createRole(1, "self", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.addChildRole(parentRoleId = role.id!!, childRoleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `addChildRole - cycle detection`() {
        val a = (svc.createRole(1, "role-a", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val b = (svc.createRole(1, "role-b", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val c = (svc.createRole(1, "role-c", null, RoleScope.TENANT, null) as AdminResult.Success).value

        // a -> b -> c, then try c -> a (would create cycle)
        svc.addChildRole(a.id!!, b.id!!, 1)
        svc.addChildRole(b.id!!, c.id!!, 1)
        val result = svc.addChildRole(c.id!!, a.id!!, 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertTrue(result.error.message.contains("circular"))
    }

    @Test
    fun `addChildRole - success`() {
        val parent = (svc.createRole(1, "parent", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val child = (svc.createRole(1, "child", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.addChildRole(parent.id!!, child.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
    }

    @Test
    fun `removeChildRole - success`() {
        val parent = (svc.createRole(1, "parent", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val child = (svc.createRole(1, "child", null, RoleScope.TENANT, null) as AdminResult.Success).value
        svc.addChildRole(parent.id!!, child.id!!, 1)
        val result = svc.removeChildRole(parent.id!!, child.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
    }

    // =========================================================================
    // User ↔ Role assignment
    // =========================================================================

    @Test
    fun `assignRoleToUser - user not found`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.assignRoleToUser(userId = 999, roleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `assignRoleToUser - role not found`() {
        val result = svc.assignRoleToUser(userId = 10, roleId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `assignRoleToUser - tenant mismatch`() {
        val otherTenant = tenants.add(Tenant(id = 50, slug = "other", displayName = "Other", issuerUrl = null))
        val role = roles.add(Role(tenantId = otherTenant.id, name = "admin", scope = RoleScope.TENANT))
        val result = svc.assignRoleToUser(userId = 10, roleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `assignRoleToUser - success`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.assignRoleToUser(userId = 10, roleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, svc.getRolesForUser(10).size)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_ROLE_ASSIGNED))
    }

    @Test
    fun `unassignRoleFromUser - success`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        svc.assignRoleToUser(userId = 10, roleId = role.id!!, tenantId = 1)
        val result = svc.unassignRoleFromUser(userId = 10, roleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(0, svc.getRolesForUser(10).size)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_ROLE_UNASSIGNED))
    }

    @Test
    fun `getEffectiveRolesForUser - includes composite children`() {
        val parent = (svc.createRole(1, "parent", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val child = (svc.createRole(1, "child", null, RoleScope.TENANT, null) as AdminResult.Success).value
        svc.addChildRole(parent.id!!, child.id!!, 1)
        svc.assignRoleToUser(10, parent.id!!, 1)
        val effective = svc.getEffectiveRolesForUser(10, 1)
        assertEquals(2, effective.size, "Should include parent + child")
        assertTrue(effective.any { it.name == "parent" })
        assertTrue(effective.any { it.name == "child" })
    }

    // =========================================================================
    // createGroup
    // =========================================================================

    @Test
    fun `createGroup - blank name`() {
        val result = svc.createGroup(tenantId = 1, name = "  ", description = null, parentGroupId = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createGroup - invalid parent`() {
        val result = svc.createGroup(tenantId = 1, name = "child", description = null, parentGroupId = 999)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `createGroup - duplicate name at same level`() {
        svc.createGroup(1, "engineering", null, null)
        val result = svc.createGroup(1, "engineering", null, null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createGroup - same name under different parents is allowed`() {
        val parent1 = (svc.createGroup(1, "team-a", null, null) as AdminResult.Success).value
        val parent2 = (svc.createGroup(1, "team-b", null, null) as AdminResult.Success).value
        svc.createGroup(1, "devs", null, parent1.id)
        val result = svc.createGroup(1, "devs", null, parent2.id)
        assertIs<AdminResult.Success<Group>>(result)
    }

    @Test
    fun `createGroup - success`() {
        val result = svc.createGroup(1, "engineering", "Engineering team", null)
        assertIs<AdminResult.Success<Group>>(result)
        assertEquals("engineering", result.value.name)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_CREATED))
    }

    // =========================================================================
    // updateGroup
    // =========================================================================

    @Test
    fun `updateGroup - not found`() {
        val result = svc.updateGroup(groupId = 999, tenantId = 1, name = "X", description = null)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `updateGroup - success`() {
        val group = (svc.createGroup(1, "old-name", null, null) as AdminResult.Success).value
        val result = svc.updateGroup(group.id!!, 1, "new-name", "Updated")
        assertIs<AdminResult.Success<Group>>(result)
        assertEquals("new-name", result.value.name)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_UPDATED))
    }

    // =========================================================================
    // deleteGroup
    // =========================================================================

    @Test
    fun `deleteGroup - not found`() {
        val result = svc.deleteGroup(groupId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `deleteGroup - success`() {
        val group = (svc.createGroup(1, "temp", null, null) as AdminResult.Success).value
        val result = svc.deleteGroup(group.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_DELETED))
    }

    // =========================================================================
    // Group ↔ Role assignment
    // =========================================================================

    @Test
    fun `assignRoleToGroup - group not found`() {
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.assignRoleToGroup(groupId = 999, roleId = role.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `assignRoleToGroup - role not found`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        val result = svc.assignRoleToGroup(groupId = group.id!!, roleId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `assignRoleToGroup - success`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        val result = svc.assignRoleToGroup(group.id!!, role.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_ROLE_ASSIGNED))
    }

    @Test
    fun `unassignRoleFromGroup - success`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        val role = (svc.createRole(1, "admin", null, RoleScope.TENANT, null) as AdminResult.Success).value
        svc.assignRoleToGroup(group.id!!, role.id!!, 1)
        val result = svc.unassignRoleFromGroup(group.id!!, role.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
    }

    // =========================================================================
    // User ↔ Group membership
    // =========================================================================

    @Test
    fun `addUserToGroup - user not found`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        val result = svc.addUserToGroup(userId = 999, groupId = group.id!!, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `addUserToGroup - group not found`() {
        val result = svc.addUserToGroup(userId = 10, groupId = 999, tenantId = 1)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `addUserToGroup - success`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        val result = svc.addUserToGroup(10, group.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, svc.getGroupsForUser(10).size)
        assertEquals(1, svc.getUserIdsInGroup(group.id!!).size)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_MEMBER_ADDED))
    }

    @Test
    fun `removeUserFromGroup - success`() {
        val group = (svc.createGroup(1, "eng", null, null) as AdminResult.Success).value
        svc.addUserToGroup(10, group.id!!, 1)
        val result = svc.removeUserFromGroup(10, group.id!!, 1)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(0, svc.getGroupsForUser(10).size)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_GROUP_MEMBER_REMOVED))
    }
}
