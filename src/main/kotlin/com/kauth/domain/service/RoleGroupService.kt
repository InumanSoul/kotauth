package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository

/**
 * Domain service for role and group management (Phase 3c).
 *
 * Handles CRUD, assignment, and validation for roles and groups.
 * Composite role cycle detection is enforced here.
 * All mutations are audit-logged.
 */
class RoleGroupService(
    private val roleRepository        : RoleRepository,
    private val groupRepository       : GroupRepository,
    private val tenantRepository      : TenantRepository,
    private val userRepository        : UserRepository,
    private val applicationRepository : ApplicationRepository,
    private val auditLog              : AuditLogPort
) {

    // =========================================================================
    // Role CRUD
    // =========================================================================

    fun createRole(
        tenantId    : Int,
        name        : String,
        description : String?,
        scope       : RoleScope,
        clientId    : Int?
    ): AdminResult<Role> {
        if (name.isBlank())
            return AdminResult.Failure(AdminError.Validation("Role name is required."))
        if (!name.matches(Regex("[a-zA-Z0-9._-]+")))
            return AdminResult.Failure(AdminError.Validation("Role name may only contain letters, digits, dots, underscores, and hyphens."))
        if (scope == RoleScope.CLIENT && clientId == null)
            return AdminResult.Failure(AdminError.Validation("Client-scoped roles require a client ID."))
        if (scope == RoleScope.TENANT && clientId != null)
            return AdminResult.Failure(AdminError.Validation("Tenant-scoped roles must not have a client ID."))

        // Validate client exists in this tenant
        if (clientId != null) {
            val app = applicationRepository.findById(clientId)
            if (app == null || app.tenantId != tenantId)
                return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        // Check uniqueness
        if (roleRepository.findByName(tenantId, name, scope, clientId) != null)
            return AdminResult.Failure(AdminError.Conflict("Role '$name' already exists in this scope."))

        val role = roleRepository.save(Role(
            tenantId    = tenantId,
            name        = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            scope       = scope,
            clientId    = clientId
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = clientId,
            eventType = AuditEventType.ADMIN_ROLE_CREATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("roleName" to name, "scope" to scope.value)
        ))

        return AdminResult.Success(role)
    }

    fun updateRole(
        roleId      : Int,
        tenantId    : Int,
        name        : String,
        description : String?
    ): AdminResult<Role> {
        val role = roleRepository.findById(roleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Role not found."))
        if (role.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Role not found in this workspace."))
        if (name.isBlank())
            return AdminResult.Failure(AdminError.Validation("Role name is required."))
        if (!name.matches(Regex("[a-zA-Z0-9._-]+")))
            return AdminResult.Failure(AdminError.Validation("Role name may only contain letters, digits, dots, underscores, and hyphens."))

        // Check uniqueness if name changed
        if (name != role.name) {
            if (roleRepository.findByName(tenantId, name, role.scope, role.clientId) != null)
                return AdminResult.Failure(AdminError.Conflict("Role '$name' already exists in this scope."))
        }

        val updated = roleRepository.update(role.copy(
            name        = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() }
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = role.clientId,
            eventType = AuditEventType.ADMIN_ROLE_UPDATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("roleId" to roleId.toString(), "roleName" to name)
        ))

        return AdminResult.Success(updated)
    }

    fun deleteRole(roleId: Int, tenantId: Int): AdminResult<Unit> {
        val role = roleRepository.findById(roleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Role not found."))
        if (role.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Role not found in this workspace."))

        roleRepository.delete(roleId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = role.clientId,
            eventType = AuditEventType.ADMIN_ROLE_DELETED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("roleName" to role.name)
        ))

        return AdminResult.Success(Unit)
    }

    fun listRoles(tenantId: Int): List<Role> = roleRepository.findByTenantId(tenantId)

    fun listClientRoles(tenantId: Int, clientId: Int): List<Role> =
        roleRepository.findByClientId(tenantId, clientId)

    // =========================================================================
    // Composite role management
    // =========================================================================

    fun addChildRole(parentRoleId: Int, childRoleId: Int, tenantId: Int): AdminResult<Unit> {
        val parent = roleRepository.findById(parentRoleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Parent role not found."))
        val child = roleRepository.findById(childRoleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Child role not found."))
        if (parent.tenantId != tenantId || child.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("Both roles must belong to the same workspace."))
        if (parentRoleId == childRoleId)
            return AdminResult.Failure(AdminError.Validation("A role cannot include itself."))

        // Cycle detection: would adding this edge create a cycle?
        if (wouldCreateCycle(parentRoleId, childRoleId))
            return AdminResult.Failure(AdminError.Validation("Adding this child role would create a circular dependency."))

        roleRepository.addChildRole(parentRoleId, childRoleId)
        return AdminResult.Success(Unit)
    }

    fun removeChildRole(parentRoleId: Int, childRoleId: Int, tenantId: Int): AdminResult<Unit> {
        val parent = roleRepository.findById(parentRoleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Parent role not found."))
        if (parent.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Role not found in this workspace."))

        roleRepository.removeChildRole(parentRoleId, childRoleId)
        return AdminResult.Success(Unit)
    }

    /**
     * Detects if adding parentId → childId would create a cycle.
     * Walks the existing children of [childRoleId] to see if [parentRoleId] is reachable.
     */
    private fun wouldCreateCycle(parentRoleId: Int, childRoleId: Int): Boolean {
        val visited = mutableSetOf(parentRoleId)
        val queue = ArrayDeque(listOf(childRoleId))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == parentRoleId) return true
            if (!visited.add(current)) continue
            queue.addAll(roleRepository.findChildRoleIds(current))
        }
        return false
    }

    // =========================================================================
    // User ↔ Role assignment
    // =========================================================================

    fun assignRoleToUser(userId: Int, roleId: Int, tenantId: Int): AdminResult<Unit> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User not found."))
        val role = roleRepository.findById(roleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Role not found."))
        if (user.tenantId != tenantId || role.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("User and role must belong to the same workspace."))

        roleRepository.assignRoleToUser(userId, roleId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = role.clientId,
            eventType = AuditEventType.ADMIN_ROLE_ASSIGNED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("roleName" to role.name, "username" to user.username)
        ))

        return AdminResult.Success(Unit)
    }

    fun unassignRoleFromUser(userId: Int, roleId: Int, tenantId: Int): AdminResult<Unit> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User not found."))
        val role = roleRepository.findById(roleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Role not found."))
        if (user.tenantId != tenantId || role.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("User and role must belong to the same workspace."))

        roleRepository.unassignRoleFromUser(userId, roleId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = role.clientId,
            eventType = AuditEventType.ADMIN_ROLE_UNASSIGNED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("roleName" to role.name, "username" to user.username)
        ))

        return AdminResult.Success(Unit)
    }

    fun getRolesForUser(userId: Int): List<Role> = roleRepository.findRolesForUser(userId)

    fun getEffectiveRolesForUser(userId: Int, tenantId: Int): List<Role> =
        roleRepository.resolveEffectiveRoles(userId, tenantId)

    // =========================================================================
    // Group CRUD
    // =========================================================================

    fun createGroup(
        tenantId      : Int,
        name          : String,
        description   : String?,
        parentGroupId : Int?
    ): AdminResult<Group> {
        if (name.isBlank())
            return AdminResult.Failure(AdminError.Validation("Group name is required."))

        // Validate parent group if specified
        if (parentGroupId != null) {
            val parent = groupRepository.findById(parentGroupId)
            if (parent == null || parent.tenantId != tenantId)
                return AdminResult.Failure(AdminError.NotFound("Parent group not found in this workspace."))
        }

        // Check uniqueness within siblings
        if (groupRepository.findByName(tenantId, name, parentGroupId) != null)
            return AdminResult.Failure(AdminError.Conflict("Group '$name' already exists at this level."))

        val group = groupRepository.save(Group(
            tenantId      = tenantId,
            name          = name.trim(),
            description   = description?.trim()?.takeIf { it.isNotBlank() },
            parentGroupId = parentGroupId
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = null,
            eventType = AuditEventType.ADMIN_GROUP_CREATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupName" to name)
        ))

        return AdminResult.Success(group)
    }

    fun updateGroup(
        groupId     : Int,
        tenantId    : Int,
        name        : String,
        description : String?
    ): AdminResult<Group> {
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        if (group.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Group not found in this workspace."))
        if (name.isBlank())
            return AdminResult.Failure(AdminError.Validation("Group name is required."))

        if (name != group.name) {
            if (groupRepository.findByName(tenantId, name, group.parentGroupId) != null)
                return AdminResult.Failure(AdminError.Conflict("Group '$name' already exists at this level."))
        }

        val updated = groupRepository.update(group.copy(
            name        = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() }
        ))

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = null,
            eventType = AuditEventType.ADMIN_GROUP_UPDATED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupId" to groupId.toString(), "groupName" to name)
        ))

        return AdminResult.Success(updated)
    }

    fun deleteGroup(groupId: Int, tenantId: Int): AdminResult<Unit> {
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        if (group.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Group not found in this workspace."))

        groupRepository.delete(groupId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = null,
            eventType = AuditEventType.ADMIN_GROUP_DELETED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupName" to group.name)
        ))

        return AdminResult.Success(Unit)
    }

    fun listGroups(tenantId: Int): List<Group> = groupRepository.findByTenantId(tenantId)

    // =========================================================================
    // Group ↔ Role assignment
    // =========================================================================

    fun assignRoleToGroup(groupId: Int, roleId: Int, tenantId: Int): AdminResult<Unit> {
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        val role = roleRepository.findById(roleId)
            ?: return AdminResult.Failure(AdminError.NotFound("Role not found."))
        if (group.tenantId != tenantId || role.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("Group and role must belong to the same workspace."))

        groupRepository.assignRoleToGroup(groupId, roleId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = null,
            clientId  = role.clientId,
            eventType = AuditEventType.ADMIN_GROUP_ROLE_ASSIGNED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupName" to group.name, "roleName" to role.name)
        ))

        return AdminResult.Success(Unit)
    }

    fun unassignRoleFromGroup(groupId: Int, roleId: Int, tenantId: Int): AdminResult<Unit> {
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        if (group.tenantId != tenantId)
            return AdminResult.Failure(AdminError.NotFound("Group not found in this workspace."))

        groupRepository.unassignRoleFromGroup(groupId, roleId)
        return AdminResult.Success(Unit)
    }

    // =========================================================================
    // User ↔ Group membership
    // =========================================================================

    fun addUserToGroup(userId: Int, groupId: Int, tenantId: Int): AdminResult<Unit> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User not found."))
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        if (user.tenantId != tenantId || group.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("User and group must belong to the same workspace."))

        groupRepository.addUserToGroup(userId, groupId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = null,
            eventType = AuditEventType.ADMIN_GROUP_MEMBER_ADDED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupName" to group.name, "username" to user.username)
        ))

        return AdminResult.Success(Unit)
    }

    fun removeUserFromGroup(userId: Int, groupId: Int, tenantId: Int): AdminResult<Unit> {
        val user = userRepository.findById(userId)
            ?: return AdminResult.Failure(AdminError.NotFound("User not found."))
        val group = groupRepository.findById(groupId)
            ?: return AdminResult.Failure(AdminError.NotFound("Group not found."))
        if (user.tenantId != tenantId || group.tenantId != tenantId)
            return AdminResult.Failure(AdminError.Validation("User and group must belong to the same workspace."))

        groupRepository.removeUserFromGroup(userId, groupId)

        auditLog.record(AuditEvent(
            tenantId  = tenantId,
            userId    = userId,
            clientId  = null,
            eventType = AuditEventType.ADMIN_GROUP_MEMBER_REMOVED,
            ipAddress = null,
            userAgent = null,
            details   = mapOf("groupName" to group.name, "username" to user.username)
        ))

        return AdminResult.Success(Unit)
    }

    fun getGroupsForUser(userId: Int): List<Group> = groupRepository.findGroupsForUser(userId)

    fun getUserIdsInGroup(groupId: Int): List<Int> = groupRepository.findUserIdsInGroup(groupId)
}
