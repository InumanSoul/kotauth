package com.kauth.domain.service

import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.RoleRepository

/**
 * Domain service — resolves the set of applications a user can see in the
 * portal launcher.
 *
 * Visibility rules:
 *   - Application is enabled
 *   - launcher_visible = true
 *   - launcher_url is set
 *
 * Entitlement rules:
 *   - Apps with zero client-scoped roles are visible to every user in the tenant
 *     (open access — common for landing pages or shared tools).
 *   - Apps with one or more client-scoped roles are visible only to users who
 *     hold at least one of those roles (via direct assignment, group, or
 *     composite expansion — delegated to [RoleRepository.resolveEffectiveRoles]).
 *
 * Sort order: launcherDisplayOrder ascending, then name ascending.
 */
class LauncherService(
    private val applicationRepository: ApplicationRepository,
    private val roleRepository: RoleRepository,
) {
    fun resolveLauncherApps(
        userId: UserId,
        tenantId: TenantId,
    ): List<Application> {
        val candidateApps =
            applicationRepository
                .findByTenantId(tenantId)
                .filter { it.enabled && it.launcherVisible && !it.launcherUrl.isNullOrBlank() }

        if (candidateApps.isEmpty()) return emptyList()

        val appsWithClientRoles: Set<ApplicationId> =
            roleRepository
                .findByTenantId(tenantId)
                .asSequence()
                .filter { it.scope == RoleScope.CLIENT }
                .mapNotNull { it.clientId }
                .toSet()

        val userClientRoleAppIds: Set<ApplicationId> =
            roleRepository
                .resolveEffectiveRoles(userId, tenantId)
                .asSequence()
                .filter { it.scope == RoleScope.CLIENT }
                .mapNotNull { it.clientId }
                .toSet()

        return candidateApps
            .filter { app ->
                app.id !in appsWithClientRoles || app.id in userClientRoleAppIds
            }.sortedWith(compareBy({ it.launcherDisplayOrder }, { it.name.lowercase() }))
    }
}
