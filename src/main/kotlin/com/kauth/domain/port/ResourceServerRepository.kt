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

    fun findByIdentifiers(
        tenantId: TenantId,
        identifiers: Set<String>,
    ): List<ResourceServer>

    fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
        scopes: List<String> = emptyList(),
    ): ResourceServer

    fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
        scopes: List<String> = emptyList(),
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

    fun setAuthorizedResources(
        clientPk: ApplicationId,
        resourceServerIds: List<ResourceServerId>,
    ): ResourceAuthorizationError?

    /**
     * The scopes this client may actually request, per resource server it is authorized for.
     *
     * A resource server declares what it offers; this says which of those a given client was
     * granted. An absent or empty entry means the client may request nothing on that resource —
     * never "everything". Treating absence as unrestricted is what made authorizing a client
     * against an API grant it every scope that API declared.
     */
    fun findAllowedScopes(clientPk: ApplicationId): Map<ResourceServerId, Set<String>>

    /**
     * Replaces the client's allowed scopes for one resource server it is already authorized for.
     * Scopes not declared by that resource server are rejected rather than silently stored.
     */
    fun setAllowedScopes(
        clientPk: ApplicationId,
        resourceServerId: ResourceServerId,
        scopes: Set<String>,
    ): ResourceAuthorizationError?
}

sealed class ResourceAuthorizationError {
    object CrossTenant : ResourceAuthorizationError()

    object UnknownClient : ResourceAuthorizationError()

    data class UnknownResource(
        val id: ResourceServerId,
    ) : ResourceAuthorizationError()

    /** A scope was granted to a client that the resource server does not declare. */
    data class UndeclaredScope(
        val id: ResourceServerId,
        val scopes: Set<String>,
    ) : ResourceAuthorizationError()

    /** The client is not authorized for the resource server at all, so it has no scopes there. */
    data class NotAuthorizedForResource(
        val id: ResourceServerId,
    ) : ResourceAuthorizationError()
}
