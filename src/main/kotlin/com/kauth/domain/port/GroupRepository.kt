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

    /**
     * Returns groups in a tenant, optionally paginated. Unbounded by default for existing callers.
     *
     * Passing `loadRoles = false` leaves every returned [Group.roleIds] empty instead of loading
     * it — for callers, like SCIM, that never read roles and would otherwise pay one extra query
     * per row for data they discard. The empty list then means "not loaded", not "no roles", so
     * it must never be read as an answer; it is a parameter rather than a separate method so that
     * the omission is visible at the call site.
     */
    fun findByTenantId(
        tenantId: TenantId,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
        loadRoles: Boolean = true,
    ): List<Group>

    /** Returns total count of groups in [tenantId]. Used for pagination. */
    fun countByTenantId(tenantId: TenantId): Long

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
     * Batches [findUserIdsInGroup] across many groups in a single query, grouped in memory by
     * group id. A group with no members has no entry in the result map. Exists so a page of SCIM
     * `/Groups` results can load membership once instead of once per group.
     */
    fun findUserIdsForGroups(groupIds: List<GroupId>): Map<GroupId, List<UserId>>

    /**
     * Returns all ancestor group IDs for a group (walking up the hierarchy).
     * Used for role inheritance resolution.
     */
    fun findAncestorGroupIds(groupId: GroupId): List<GroupId>
}
