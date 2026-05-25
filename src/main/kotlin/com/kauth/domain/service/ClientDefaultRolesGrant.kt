package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.RoleRepository

/**
 * Grants the originating client's configured default roles to a freshly
 * registered user. No-op when there is no originating client, the client is
 * unknown, or the repositories were not wired (test setups).
 *
 * Shared by registration, social login, and email-OTP verification paths so
 * each entry point grants the same default-role bundle for the same client.
 */
fun applyClientDefaultRolesGrant(
    tenantId: TenantId,
    userId: UserId,
    originatingClientId: String?,
    applicationRepository: ApplicationRepository?,
    roleRepository: RoleRepository?,
) {
    if (originatingClientId == null) return
    val apps = applicationRepository ?: return
    val roles = roleRepository ?: return
    val app = apps.findByClientId(tenantId, originatingClientId) ?: return
    roles.findDefaultRolesForClient(app.id).forEach { role ->
        roles.assignRoleToUser(userId, role.id!!)
    }
}
