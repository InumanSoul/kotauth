package com.kauth.fakes

import com.kauth.domain.model.Group
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
        val g = if (group.id == null) group.copy(id = nextId++) else group
        store[g.id!!] = g
        return g
    }

    fun clear() {
        store.clear()
        groupRoles.clear()
        groupMembers.clear()
        nextId = 1
    }

    override fun findById(id: Int): Group? = store[id]

    override fun findByTenantId(tenantId: Int): List<Group> =
        store.values.filter { it.tenantId == tenantId }

    override fun findByName(tenantId: Int, name: String, parentGroupId: Int?): Group? =
        store.values.find {
            it.tenantId == tenantId && it.name == name && it.parentGroupId == parentGroupId
        }

    override fun findChildren(groupId: Int): List<Group> =
        store.values.filter { it.parentGroupId == groupId }

    override fun save(group: Group): Group {
        val g = if (group.id == null) group.copy(id = nextId++) else group
        store[g.id!!] = g
        return g
    }

    override fun update(group: Group): Group {
        store[group.id!!] = group
        return group
    }

    override fun delete(groupId: Int) {
        store.remove(groupId)
        groupRoles.remove(groupId)
        groupMembers.remove(groupId)
    }

    override fun assignRoleToGroup(groupId: Int, roleId: Int) {
        groupRoles.getOrPut(groupId) { mutableSetOf() }.add(roleId)
    }

    override fun unassignRoleFromGroup(groupId: Int, roleId: Int) {
        groupRoles[groupId]?.remove(roleId)
    }

    override fun findRoleIdsForGroup(groupId: Int): List<Int> =
        groupRoles[groupId]?.toList() ?: emptyList()

    override fun addUserToGroup(userId: Int, groupId: Int) {
        groupMembers.getOrPut(groupId) { mutableSetOf() }.add(userId)
    }

    override fun removeUserFromGroup(userId: Int, groupId: Int) {
        groupMembers[groupId]?.remove(userId)
    }

    override fun findGroupsForUser(userId: Int): List<Group> =
        groupMembers.filter { it.value.contains(userId) }
            .keys
            .mapNotNull { store[it] }

    override fun findUserIdsInGroup(groupId: Int): List<Int> =
        groupMembers[groupId]?.toList() ?: emptyList()

    override fun findAncestorGroupIds(groupId: Int): List<Int> {
        val ancestors = mutableListOf<Int>()
        var current = store[groupId]?.parentGroupId
        while (current != null) {
            ancestors.add(current)
            current = store[current]?.parentGroupId
        }
        return ancestors
    }
}
