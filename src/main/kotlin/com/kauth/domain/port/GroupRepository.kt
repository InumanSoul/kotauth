package com.kauth.domain.port

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId

/**
 * Port for group persistence — tenant-scoped CRUD + membership operations.
 */
interface GroupRepository {
    fun findById(id: GroupId): Group?

    fun findByTenantId(tenantId: TenantId): List<Group>

    fun findByName(
        tenantId: TenantId,
        name: String,
        parentGroupId: GroupId? = null,
    ): Group?

    fun findChildren(groupId: GroupId): List<Group>

    /** Finds a group by the identity provider's key. Null when unprovisioned or unknown. */
    fun findByExternalId(
        tenantId: TenantId,
        externalId: String,
    ): Group?

    fun save(group: Group): Group

    fun update(group: Group): Group

    fun delete(groupId: GroupId)

    // Group ↔ Role assignment
    fun assignRoleToGroup(
        groupId: GroupId,
        roleId: RoleId,
    )

    fun unassignRoleFromGroup(
        groupId: GroupId,
        roleId: RoleId,
    )

    fun findRoleIdsForGroup(groupId: GroupId): List<RoleId>

    // User ↔ Group membership
    fun addUserToGroup(
        userId: UserId,
        groupId: GroupId,
    )

    fun removeUserFromGroup(
        userId: UserId,
        groupId: GroupId,
    )

    fun findGroupsForUser(userId: UserId): List<Group>

    /**
     * Batches [findGroupsForUser] across many users in a single query, grouped in memory by
     * user id. A user with no memberships has no entry in the result map. Exists so a page of
     * SCIM results can load groups once instead of once per user.
     */
    fun findGroupsForUsers(userIds: List<UserId>): Map<UserId, List<Group>>

    fun findUserIdsInGroup(groupId: GroupId): List<UserId>

    /**
     * Returns all ancestor group IDs for a group (walking up the hierarchy).
     * Used for role inheritance resolution.
     */
    fun findAncestorGroupIds(groupId: GroupId): List<GroupId>
}
