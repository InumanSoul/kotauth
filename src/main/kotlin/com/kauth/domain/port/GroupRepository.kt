package com.kauth.domain.port

import com.kauth.domain.model.Group

/**
 * Port for group persistence — tenant-scoped CRUD + membership operations.
 */
interface GroupRepository {
    fun findById(id: Int): Group?
    fun findByTenantId(tenantId: Int): List<Group>
    fun findByName(tenantId: Int, name: String, parentGroupId: Int? = null): Group?
    fun findChildren(groupId: Int): List<Group>
    fun save(group: Group): Group
    fun update(group: Group): Group
    fun delete(groupId: Int)

    // Group ↔ Role assignment
    fun assignRoleToGroup(groupId: Int, roleId: Int)
    fun unassignRoleFromGroup(groupId: Int, roleId: Int)
    fun findRoleIdsForGroup(groupId: Int): List<Int>

    // User ↔ Group membership
    fun addUserToGroup(userId: Int, groupId: Int)
    fun removeUserFromGroup(userId: Int, groupId: Int)
    fun findGroupsForUser(userId: Int): List<Group>
    fun findUserIdsInGroup(groupId: Int): List<Int>

    /**
     * Returns all ancestor group IDs for a group (walking up the hierarchy).
     * Used for role inheritance resolution.
     */
    fun findAncestorGroupIds(groupId: Int): List<Int>
}
