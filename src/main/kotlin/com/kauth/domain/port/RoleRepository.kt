package com.kauth.domain.port

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId

/**
 * Port for role persistence — tenant-scoped CRUD + assignment operations.
 *
 * All queries are scoped to a tenant. Cross-tenant role access is architecturally impossible.
 */
interface RoleRepository {
    fun findById(id: RoleId): Role?

    fun findByName(
        tenantId: TenantId,
        name: String,
        scope: RoleScope,
        clientId: ApplicationId? = null,
    ): Role?

    fun findByTenantId(tenantId: TenantId): List<Role>

    fun findByClientId(
        tenantId: TenantId,
        clientId: ApplicationId,
    ): List<Role>

    fun save(role: Role): Role

    fun update(role: Role): Role

    fun delete(roleId: RoleId)

    // Composite role management
    fun addChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    )

    fun removeChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    )

    fun findChildRoleIds(roleId: RoleId): List<RoleId>

    /** Returns all parent→children composite mappings for roles in the given tenant (single query). */
    fun findAllChildMappings(tenantId: TenantId): Map<RoleId, List<RoleId>>

    // User ↔ Role assignment
    fun assignRoleToUser(
        userId: UserId,
        roleId: RoleId,
    )

    fun unassignRoleFromUser(
        userId: UserId,
        roleId: RoleId,
    )

    fun findRolesForUser(userId: UserId): List<Role>

    fun findUserIdsForRole(roleId: RoleId): List<UserId>

    /**
     * Resolves all effective roles for a user, including:
     *   - Directly assigned roles
     *   - Roles inherited from groups (and ancestor groups)
     *   - Roles inherited via composite role expansion
     *
     * This is the method that [TokenPort] should use when building token claims.
     */
    fun resolveEffectiveRoles(
        userId: UserId,
        tenantId: TenantId,
    ): List<Role>
}
