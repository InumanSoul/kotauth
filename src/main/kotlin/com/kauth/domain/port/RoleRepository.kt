package com.kauth.domain.port

import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope

/**
 * Port for role persistence — tenant-scoped CRUD + assignment operations.
 *
 * All queries are scoped to a tenant. Cross-tenant role access is architecturally impossible.
 */
interface RoleRepository {
    fun findById(id: Int): Role?
    fun findByName(tenantId: Int, name: String, scope: RoleScope, clientId: Int? = null): Role?
    fun findByTenantId(tenantId: Int): List<Role>
    fun findByClientId(tenantId: Int, clientId: Int): List<Role>
    fun save(role: Role): Role
    fun update(role: Role): Role
    fun delete(roleId: Int)

    // Composite role management
    fun addChildRole(parentRoleId: Int, childRoleId: Int)
    fun removeChildRole(parentRoleId: Int, childRoleId: Int)
    fun findChildRoleIds(roleId: Int): List<Int>

    // User ↔ Role assignment
    fun assignRoleToUser(userId: Int, roleId: Int)
    fun unassignRoleFromUser(userId: Int, roleId: Int)
    fun findRolesForUser(userId: Int): List<Role>
    fun findUserIdsForRole(roleId: Int): List<Int>

    /**
     * Resolves all effective roles for a user, including:
     *   - Directly assigned roles
     *   - Roles inherited from groups (and ancestor groups)
     *   - Roles inherited via composite role expansion
     *
     * This is the method that [TokenPort] should use when building token claims.
     */
    fun resolveEffectiveRoles(userId: Int, tenantId: Int): List<Role>
}
