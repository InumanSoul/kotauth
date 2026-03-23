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
class FakeGroupRepository : GroupRepository {
    private val store = mutableMapOf<Int, Group>()
    private var nextId = 1

    // groupId -> set of roleIds
    private val groupRoles = mutableMapOf<Int, MutableSet<Int>>()

    // groupId -> set of userIds
    private val groupMembers = mutableMapOf<Int, MutableSet<Int>>()

    fun add(group: Group): Group {
        val g = if (group.id == null) group.copy(id = GroupId(nextId++)) else group
        store[g.id!!.value] = g
        return g
    }

    fun clear() {
        store.clear()
        groupRoles.clear()
        groupMembers.clear()
        nextId = 1
    }

    override fun findById(id: GroupId): Group? = store[id.value]

    override fun findByTenantId(tenantId: TenantId): List<Group> = store.values.filter { it.tenantId == tenantId }

    override fun findByName(
        tenantId: TenantId,
        name: String,
        parentGroupId: GroupId?,
    ): Group? =
        store.values.find {
            it.tenantId == tenantId && it.name == name && it.parentGroupId == parentGroupId
        }

    override fun findChildren(groupId: GroupId): List<Group> = store.values.filter { it.parentGroupId == groupId }

    override fun save(group: Group): Group {
        val g = if (group.id == null) group.copy(id = GroupId(nextId++)) else group
        store[g.id!!.value] = g
        return g
    }

    override fun update(group: Group): Group {
        store[group.id!!.value] = group
        return group
    }

    override fun delete(groupId: GroupId) {
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

    override fun addUserToGroup(
        userId: UserId,
        groupId: GroupId,
    ) {
        groupMembers.getOrPut(groupId.value) { mutableSetOf() }.add(userId.value)
    }

    override fun removeUserFromGroup(
        userId: UserId,
        groupId: GroupId,
    ) {
        groupMembers[groupId.value]?.remove(userId.value)
    }

    override fun findGroupsForUser(userId: UserId): List<Group> =
        groupMembers
            .filter { it.value.contains(userId.value) }
            .keys
            .mapNotNull { store[it] }

    override fun findUserIdsInGroup(groupId: GroupId): List<UserId> =
        groupMembers[groupId.value]?.map { UserId(it) }?.toList() ?: emptyList()

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
