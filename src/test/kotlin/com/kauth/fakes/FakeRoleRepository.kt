package com.kauth.fakes

import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.port.RoleRepository

/**
 * In-memory RoleRepository for unit tests.
 * Supports composite roles, user assignments, and effective role resolution.
 */
class FakeRoleRepository : RoleRepository {
    private val store = mutableMapOf<Int, Role>()
    private var nextId = 1

    // parentRoleId -> set of childRoleIds
    private val composites = mutableMapOf<Int, MutableSet<Int>>()

    // userId -> set of roleIds
    private val userRoles = mutableMapOf<Int, MutableSet<Int>>()

    fun add(role: Role): Role {
        val r = if (role.id == null) role.copy(id = nextId++) else role
        store[r.id!!] = r
        return r
    }

    fun clear() {
        store.clear()
        composites.clear()
        userRoles.clear()
        nextId = 1
    }

    override fun findById(id: Int): Role? = store[id]

    override fun findByName(
        tenantId: Int,
        name: String,
        scope: RoleScope,
        clientId: Int?,
    ): Role? =
        store.values.find {
            it.tenantId == tenantId && it.name == name && it.scope == scope && it.clientId == clientId
        }

    override fun findByTenantId(tenantId: Int): List<Role> = store.values.filter { it.tenantId == tenantId }

    override fun findByClientId(
        tenantId: Int,
        clientId: Int,
    ): List<Role> = store.values.filter { it.tenantId == tenantId && it.clientId == clientId }

    override fun save(role: Role): Role {
        val r = if (role.id == null) role.copy(id = nextId++) else role
        store[r.id!!] = r
        return r
    }

    override fun update(role: Role): Role {
        store[role.id!!] = role
        return role
    }

    override fun delete(roleId: Int) {
        store.remove(roleId)
        composites.remove(roleId)
        composites.values.forEach { it.remove(roleId) }
        userRoles.values.forEach { it.remove(roleId) }
    }

    override fun addChildRole(
        parentRoleId: Int,
        childRoleId: Int,
    ) {
        composites.getOrPut(parentRoleId) { mutableSetOf() }.add(childRoleId)
    }

    override fun removeChildRole(
        parentRoleId: Int,
        childRoleId: Int,
    ) {
        composites[parentRoleId]?.remove(childRoleId)
    }

    override fun findChildRoleIds(roleId: Int): List<Int> = composites[roleId]?.toList() ?: emptyList()

    override fun assignRoleToUser(
        userId: Int,
        roleId: Int,
    ) {
        userRoles.getOrPut(userId) { mutableSetOf() }.add(roleId)
    }

    override fun unassignRoleFromUser(
        userId: Int,
        roleId: Int,
    ) {
        userRoles[userId]?.remove(roleId)
    }

    override fun findRolesForUser(userId: Int): List<Role> = userRoles[userId]?.mapNotNull { store[it] } ?: emptyList()

    override fun findUserIdsForRole(roleId: Int): List<Int> =
        userRoles.filter { it.value.contains(roleId) }.keys.toList()

    override fun resolveEffectiveRoles(
        userId: Int,
        tenantId: Int,
    ): List<Role> {
        val directRoleIds = userRoles[userId] ?: emptySet()
        val allRoleIds = mutableSetOf<Int>()
        val queue = ArrayDeque(directRoleIds.toList())
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (allRoleIds.add(id)) {
                queue.addAll(composites[id] ?: emptySet())
            }
        }
        return allRoleIds.mapNotNull { store[it] }.filter { it.tenantId == tenantId }
    }
}
