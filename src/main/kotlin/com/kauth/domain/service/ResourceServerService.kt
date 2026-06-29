package com.kauth.domain.service

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ResourceAuthorizationError
import com.kauth.domain.port.ResourceServerRepository
import com.kauth.domain.util.validateResourceIdentifier

class ResourceServerService(
    private val repo: ResourceServerRepository,
) {
    fun list(tenantId: TenantId): List<ResourceServer> = repo.findByTenantId(tenantId)

    fun get(
        tenantId: TenantId,
        id: ResourceServerId,
    ): ResourceServer? = repo.findById(tenantId, id)

    fun create(
        tenantId: TenantId,
        identifier: String,
        name: String,
        description: String?,
        scopes: List<String> = emptyList(),
    ): ResourceServerResult<ResourceServer> {
        val trimmedIdentifier = identifier.trim()
        val trimmedName = name.trim()
        validateResourceIdentifier(trimmedIdentifier)?.let {
            return ResourceServerResult.Failure(ResourceServerError.InvalidIdentifier(it))
        }
        if (trimmedName.isBlank()) return ResourceServerResult.Failure(ResourceServerError.InvalidName)
        if (repo.findByIdentifier(tenantId, trimmedIdentifier) != null) {
            return ResourceServerResult.Failure(ResourceServerError.IdentifierAlreadyExists)
        }
        return ResourceServerResult.Success(
            repo.create(tenantId, trimmedIdentifier, trimmedName, description?.trim()?.ifBlank { null }, scopes),
        )
    }

    fun update(
        tenantId: TenantId,
        id: ResourceServerId,
        name: String,
        description: String?,
        scopes: List<String> = emptyList(),
    ): ResourceServerResult<ResourceServer> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return ResourceServerResult.Failure(ResourceServerError.InvalidName)
        if (repo.findById(tenantId, id) == null) {
            return ResourceServerResult.Failure(ResourceServerError.NotFound)
        }
        return ResourceServerResult.Success(
            repo.update(tenantId, id, trimmedName, description?.trim()?.ifBlank { null }, scopes),
        )
    }

    fun setEnabled(
        tenantId: TenantId,
        id: ResourceServerId,
        enabled: Boolean,
    ): ResourceServerResult<Unit> {
        if (repo.findById(tenantId, id) == null) {
            return ResourceServerResult.Failure(ResourceServerError.NotFound)
        }
        repo.setEnabled(tenantId, id, enabled)
        return ResourceServerResult.Success(Unit)
    }

    fun delete(
        tenantId: TenantId,
        id: ResourceServerId,
    ): ResourceServerResult<Unit> {
        if (repo.findById(tenantId, id) == null) {
            return ResourceServerResult.Failure(ResourceServerError.NotFound)
        }
        repo.delete(tenantId, id)
        return ResourceServerResult.Success(Unit)
    }

    fun listAuthorized(clientPk: ApplicationId): List<ResourceServer> = repo.listAuthorizedFor(clientPk)

    fun setAuthorized(
        clientPk: ApplicationId,
        resourceServerIds: List<ResourceServerId>,
    ): ResourceServerResult<Unit> {
        val error = repo.setAuthorizedResources(clientPk, resourceServerIds)
        return when (error) {
            null -> ResourceServerResult.Success(Unit)
            is ResourceAuthorizationError.CrossTenant ->
                ResourceServerResult.Failure(ResourceServerError.CrossTenant)
            is ResourceAuthorizationError.UnknownClient ->
                ResourceServerResult.Failure(ResourceServerError.NotFound)
            is ResourceAuthorizationError.UnknownResource ->
                ResourceServerResult.Failure(ResourceServerError.NotFound)
        }
    }
}

sealed class ResourceServerResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ResourceServerResult<T>()

    data class Failure(
        val error: ResourceServerError,
    ) : ResourceServerResult<Nothing>()
}

sealed class ResourceServerError {
    data class InvalidIdentifier(
        val reason: String,
    ) : ResourceServerError()

    object InvalidName : ResourceServerError()

    object IdentifierAlreadyExists : ResourceServerError()

    object NotFound : ResourceServerError()

    object CrossTenant : ResourceServerError()
}
