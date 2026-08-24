package com.kauth.fakes

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.GroupRepository

/**
 * In-memory GroupRepository for unit tests.
 * Supports hierarchy, role assignments, and user membership.
 */
class FakeGroupRepository :
    GroupRepository,
    SnapshotableFake {
    private val store = mutableMapOf<Int, Group>()
    private var nextId = 1

    // groupId -> set of roleIds
    private val groupRoles = mutableMapOf<Int, MutableSet<Int>>()

    // groupId -> set of userIds
    private val groupMembers = mutableMapOf<Int, MutableSet<Int>>()

    fun add(group: Group): Group = save(group)

    /**
     * [nextId] is deliberately not captured: a database sequence does not give back the ids a
     * rolled-back insert consumed, so neither does this.
     */
    override fun snapshot(): FakeRestore {
        val storeCopy = store.toMap()
        val rolesCopy = groupRoles.mapValues { (_, roles) -> roles.toMutableSet() }
        val membersCopy = groupMembers.mapValues { (_, members) -> members.toMutableSet() }
        return FakeRestore {
            store.clear()
            store.putAll(storeCopy)
            groupRoles.clear()
            groupRoles.putAll(rolesCopy)
            groupMembers.clear()
            groupMembers.putAll(membersCopy)
        }
    }

    fun clear() {
        store.clear()
        groupRoles.clear()
        groupMembers.clear()
        deleteCalls.clear()
        findByTenantIdLoadRolesFlags.clear()
        findGroupsForUsersCallSizes.clear()
        findUserIdsForGroupsCallSizes.clear()
        addUserToGroupCalls.clear()
        removeUserFromGroupCalls.clear()
        nextId = 1
    }

    override fun findById(id: GroupId): Group? = store[id.value]?.withRoles()

    // Records the loadRoles flag of every findByTenantId call, so a test can prove the SCIM
    // paths ask for the cheap variant rather than merely tolerating whatever they get.
    val findByTenantIdLoadRolesFlags = mutableListOf<Boolean>()

    override fun findByTenantId(
        tenantId: TenantId,
        limit: Int,
        offset: Int,
        loadRoles: Boolean,
    ): List<Group> {
        findByTenantIdLoadRolesFlags += loadRoles
        return store.values
            .filter { it.tenantId == tenantId }
            .sortedBy { it.name }
            .drop(offset)
            .take(limit)
            .map { if (loadRoles) it.withRoles() else it.copy(roleIds = emptyList()) }
    }

    /** Roles live in [groupRoles], as they live in `group_roles` rather than on the group row. */
    private fun Group.withRoles(): Group = copy(roleIds = findRoleIdsForGroup(id!!))

    override fun countByTenantId(tenantId: TenantId): Long = store.values.count { it.tenantId == tenantId }.toLong()

    override fun findByName(
        tenantId: TenantId,
        name: String,
        parentGroupId: GroupId?,
    ): Group? =
        store.values
            .find { it.tenantId == tenantId && it.name == name && it.parentGroupId == parentGroupId }
            ?.withRoles()

    override fun findChildren(groupId: GroupId): List<Group> =
        store.values.filter { it.parentGroupId == groupId }.map { it.withRoles() }

    override fun findByExternalId(
        tenantId: TenantId,
        externalId: String,
    ): Group? = store.values.find { it.tenantId == tenantId && it.externalId == externalId }?.withRoles()

    override fun save(group: Group): Group {
        val g = if (group.id == null) group.copy(id = GroupId(nextId++)) else group
        store[g.id!!.value] = g
        // An insert seeds group_roles from the row it was handed; update() deliberately does not,
        // because the Postgres adapter's update never touches group_roles either.
        if (g.roleIds.isNotEmpty()) groupRoles[g.id.value] = g.roleIds.map { it.value }.toMutableSet()
        return g.withRoles()
    }

    override fun update(group: Group): Group {
        store[group.id!!.value] = group
        return group
    }

    // Records every delete so tests can assert a refused delete never reached the repository.
    // The fake models no FK cascade, so "the child row still exists" alone proves nothing.
    val deleteCalls = mutableListOf<GroupId>()

    override fun delete(groupId: GroupId) {
        deleteCalls += groupId
        store.remove(groupId.value)
        groupRoles.remove(groupId.value)
        groupMembers.remove(groupId.value)
    }

    override fun assignRoleToGroup(
        groupId: GroupId,
        roleId: RoleId,
    ) {
        groupRoles.getOrPut(groupId.value) { mutableSetOf() }.add(roleId.value)
    }

    override fun unassignRoleFromGroup(
        groupId: GroupId,
        roleId: RoleId,
    ) {
        groupRoles[groupId.value]?.remove(roleId.value)
    }

    override fun findRoleIdsForGroup(groupId: GroupId): List<RoleId> =
        groupRoles[groupId.value]?.map { RoleId(it) }?.toList() ?: emptyList()

    // Records every add/remove call so tests can assert a no-op reconcile issues zero writes —
    // a set-equality check on the resulting membership alone would pass either way.
    val addUserToGroupCalls = mutableListOf<Pair<UserId, GroupId>>()
    val removeUserFromGroupCalls = mutableListOf<Pair<UserId, GroupId>>()

    override fun addUserToGroup(
        userId: UserId,
        groupId: GroupId,
    ) {
        addUserToGroupCalls += userId to groupId
        groupMembers.getOrPut(groupId.value) { mutableSetOf() }.add(userId.value)
    }

    override fun removeUserFromGroup(
        userId: UserId,
        groupId: GroupId,
    ) {
        removeUserFromGroupCalls += userId to groupId
        groupMembers[groupId.value]?.remove(userId.value)
    }

    override fun findGroupsForUser(userId: UserId): List<Group> =
        groupMembers
            .filter { it.value.contains(userId.value) }
            .keys
            .mapNotNull { store[it]?.withRoles() }

    // Records the size of every findGroupsForUsers call so tests can assert the N+1 fix:
    // the number of ids looked up must track the page size, never the total match count.
    val findGroupsForUsersCallSizes = mutableListOf<Int>()

    override fun findGroupsForUsers(userIds: List<UserId>): Map<UserId, List<Group>> {
        findGroupsForUsersCallSizes += userIds.size
        return userIds
            .mapNotNull { userId ->
                val groups = findGroupsForUser(userId)
                if (groups.isEmpty()) null else userId to groups
            }.toMap()
    }

    override fun findUserIdsInGroup(groupId: GroupId): List<UserId> =
        groupMembers[groupId.value]?.map { UserId(it) }?.toList() ?: emptyList()

    // Records the size of every findUserIdsForGroups call so tests can assert the page of groups
    // loads membership in one batch rather than once per group.
    val findUserIdsForGroupsCallSizes = mutableListOf<Int>()

    override fun findUserIdsForGroups(groupIds: List<GroupId>): Map<GroupId, List<UserId>> {
        findUserIdsForGroupsCallSizes += groupIds.size
        return groupIds
            .mapNotNull { groupId ->
                val members = findUserIdsInGroup(groupId)
                if (members.isEmpty()) null else groupId to members
            }.toMap()
    }

    override fun findAncestorGroupIds(groupId: GroupId): List<GroupId> {
        val ancestors = mutableListOf<GroupId>()
        var current = store[groupId.value]?.parentGroupId
        while (current != null) {
            ancestors.add(current)
            current = store[current.value]?.parentGroupId
        }
        return ancestors
    }
}
