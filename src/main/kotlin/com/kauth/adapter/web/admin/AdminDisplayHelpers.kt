package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminService

/** Resolves a list of user IDs to a display map of userId→username. */
fun resolveUsernames(
    userIds: List<UserId>,
    tenantId: TenantId,
    adminService: AdminService,
): Map<UserId, String> =
    userIds.associateWith { uid ->
        (adminService.getUser(uid, tenantId) as? AdminResult.Success)
            ?.value
            ?.username ?: uid.value.toString()
    }

/** Resolves a list of application IDs to a display map of appId→name. */
fun resolveClientNames(
    clientIds: List<ApplicationId>,
    applicationRepository: ApplicationRepository,
): Map<ApplicationId, String> =
    clientIds.associateWith { cid ->
        applicationRepository.findById(cid)?.name ?: cid.value.toString()
    }

/** Display info for linking to application detail pages. */
data class ClientDisplayInfo(
    val name: String,
    val clientId: String,
)

/** Resolves application IDs to display info (name + clientId slug for URL). */
fun resolveClientLinks(
    appIds: List<ApplicationId>,
    applicationRepository: ApplicationRepository,
): Map<ApplicationId, ClientDisplayInfo> =
    appIds.associateWith { cid ->
        val app = applicationRepository.findById(cid)
        ClientDisplayInfo(
            name = app?.name ?: cid.value.toString(),
            clientId = app?.clientId ?: cid.value.toString(),
        )
    }
