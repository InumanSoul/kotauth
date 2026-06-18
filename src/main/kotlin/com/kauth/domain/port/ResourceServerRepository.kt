package com.kauth.domain.port

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId

interface ResourceServerRepository {
    fun findByTenantId(tenantId: TenantId): List<ResourceServer>

    fun findById(
        tenantId: TenantId,
        id: ResourceServerId,
    ): ResourceServer?

    fun findByIdentifier(
        tenantId: TenantId,
        identifier: String,
    ): ResourceServer?

    fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
    ): ResourceServer

    fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
    ): ResourceServer

    fun setEnabled(
        tenantId: TenantId,
        id: ResourceServerId,
        enabled: Boolean,
    )

    fun delete(
        tenantId: TenantId,
        id: ResourceServerId,
    )

    fun listAuthorizedFor(clientPk: ApplicationId): List<ResourceServer>

    /**
     * Returns null on success; otherwise a typed reason. The join table has no tenant_id, so
     * cross-tenant authorization can only be caught at write time — see CrossTenant.
     */
    fun setAuthorizedResources(
        clientPk: ApplicationId,
        resourceServerIds: List<ResourceServerId>,
    ): ResourceAuthorizationError?
}

sealed class ResourceAuthorizationError {
    object CrossTenant : ResourceAuthorizationError()

    object UnknownClient : ResourceAuthorizationError()

    data class UnknownResource(
        val id: ResourceServerId,
    ) : ResourceAuthorizationError()
}
