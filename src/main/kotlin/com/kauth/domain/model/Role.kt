package com.kauth.domain.model

import java.time.Instant

/**
 * A role that can be assigned to users or groups within a tenant.
 *
 * Roles come in two scopes:
 *   - [RoleScope.TENANT]: global to the workspace, included in `realm_access.roles` in tokens.
 *   - [RoleScope.CLIENT]: scoped to a specific application, included in `resource_access.{clientId}.roles`.
 *
 * Composite roles include other roles via [childRoleIds]. When a user is assigned a composite role,
 * they effectively hold all child roles as well. Cycle prevention is enforced at the service layer.
 */
data class Role(
    val id: Int? = null,
    val tenantId: Int,
    val name: String,
    val description: String? = null,
    val scope: RoleScope = RoleScope.TENANT,
    val clientId: Int? = null,
    val childRoleIds: List<Int> = emptyList(),
    val createdAt: Instant = Instant.now()
)

/**
 * Determines the visibility and claim placement of a role in issued tokens.
 */
enum class RoleScope(val value: String) {
    /** Global to the workspace — appears in `realm_access.roles`. */
    TENANT("tenant"),
    /** Scoped to a specific client/application — appears in `resource_access.{clientId}.roles`. */
    CLIENT("client");

    companion object {
        fun fromValue(value: String): RoleScope =
            entries.firstOrNull { it.value == value } ?: TENANT
    }
}
