package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
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
        val r = if (role.id == null) role.copy(id = RoleId(nextId++)) else role
        store[r.id!!.value] = r
        return r
    }

    fun clear() {
        store.clear()
        composites.clear()
        userRoles.clear()
        nextId = 1
    }

    override fun findById(id: RoleId): Role? = store[id.value]

    override fun findByName(
        tenantId: TenantId,
        name: String,
        scope: RoleScope,
        clientId: ApplicationId?,
    ): Role? =
        store.values.find {
            it.tenantId == tenantId && it.name == name && it.scope == scope && it.clientId == clientId
        }

    override fun findByTenantId(tenantId: TenantId): List<Role> = store.values.filter { it.tenantId == tenantId }

    override fun findByClientId(
        tenantId: TenantId,
        clientId: ApplicationId,
    ): List<Role> = store.values.filter { it.tenantId == tenantId && it.clientId == clientId }

    override fun save(role: Role): Role {
        val r = if (role.id == null) role.copy(id = RoleId(nextId++)) else role
        store[r.id!!.value] = r
        return r
    }

    override fun update(role: Role): Role {
        store[role.id!!.value] = role
        return role
    }

    override fun delete(roleId: RoleId) {
        store.remove(roleId.value)
        composites.remove(roleId.value)
        composites.values.forEach { it.remove(roleId.value) }
        userRoles.values.forEach { it.remove(roleId.value) }
    }

    override fun addChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    ) {
        composites.getOrPut(parentRoleId.value) { mutableSetOf() }.add(childRoleId.value)
    }

    override fun removeChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    ) {
        composites[parentRoleId.value]?.remove(childRoleId.value)
    }

    override fun findChildRoleIds(roleId: RoleId): List<RoleId> =
        composites[roleId.value]?.map { RoleId(it) } ?: emptyList()

    override fun findAllChildMappings(tenantId: TenantId): Map<RoleId, List<RoleId>> {
        val tenantRoleIds =
            store.values
                .filter { it.tenantId == tenantId }
                .mapNotNull { it.id }
                .toSet()
        return composites
            .filter { (parentId, _) -> RoleId(parentId) in tenantRoleIds }
            .mapKeys { (parentId, _) -> RoleId(parentId) }
            .mapValues { (_, childIds) -> childIds.map { RoleId(it) } }
            .filter { (_, children) -> children.isNotEmpty() }
    }

    override fun assignRoleToUser(
        userId: UserId,
        roleId: RoleId,
    ) {
        userRoles.getOrPut(userId.value) { mutableSetOf() }.add(roleId.value)
    }

    override fun unassignRoleFromUser(
        userId: UserId,
        roleId: RoleId,
    ) {
        userRoles[userId.value]?.remove(roleId.value)
    }

    override fun findRolesForUser(userId: UserId): List<Role> =
        userRoles[userId.value]?.mapNotNull { store[it] } ?: emptyList()

    override fun findUserIdsForRole(roleId: RoleId): List<UserId> =
        userRoles
            .filter { it.value.contains(roleId.value) }
            .keys
            .map { UserId(it) }
            .toList()

    override fun resolveEffectiveRoles(
        userId: UserId,
        tenantId: TenantId,
    ): List<Role> {
        val directRoleIds = userRoles[userId.value] ?: emptySet()
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
