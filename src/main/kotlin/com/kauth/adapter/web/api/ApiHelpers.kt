package com.kauth.adapter.web.api

import com.kauth.domain.model.TenantId
import com.kauth.domain.service.AdminError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

internal val ApiKeyAttr = AttributeKey<com.kauth.domain.model.ApiKey>("ApiKey")
internal val TenantIdAttr = AttributeKey<TenantId>("TenantId")

internal val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

internal suspend fun requireScope(
    call: ApplicationCall,
    scope: String,
): Unit? {
    val key =
        call.attributes.getOrNull(ApiKeyAttr)
            ?: return call.respondProblem(
                HttpStatusCode.Unauthorized,
                "Unauthorized",
                "A valid API key is required.",
            )
    if (scope !in key.scopes) {
        call.respondProblem(
            HttpStatusCode.Forbidden,
            "Insufficient scope",
            "This API key does not have the '$scope' permission.",
        )
        return null
    }
    return Unit
}

internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String,
) {
    response.headers.append(HttpHeaders.ContentType, "application/problem+json")
    respond(
        status,
        ProblemDetail(
            type = "https://kotauth.dev/errors/${status.value}",
            title = title,
            status = status.value,
            detail = detail,
        ),
    )
}

internal suspend fun ApplicationCall.respondAdminError(error: AdminError): Unit =
    when (error) {
        is AdminError.NotFound -> respondProblem(HttpStatusCode.NotFound, "Not Found", error.message)
        is AdminError.Conflict -> respondProblem(HttpStatusCode.Conflict, "Conflict", error.message)
        is AdminError.Validation ->
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "Validation Error",
                error.message,
            )
    }

// -- Response envelope --------------------------------------------------------

@Serializable
data class ApiResponse<T>(
    val data: List<T>,
    val meta: ApiMeta,
)

@Serializable
data class ApiMeta(
    val total: Int,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
)

// -- Request bodies -----------------------------------------------------------

@Serializable data class CreateUserRequest(
    val username: String,
    val email: String,
    val fullName: String,
    val password: String,
)

@Serializable data class UpdateUserRequest(
    val email: String? = null,
    val fullName: String? = null,
)

@Serializable data class CreateRoleRequest(
    val name: String,
    val description: String? = null,
    val scope: String? = "REALM",
)

@Serializable data class UpdateRoleRequest(
    val name: String,
    val description: String? = null,
)

@Serializable data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val parentGroupId: Int? = null,
)

@Serializable data class UpdateGroupRequest(
    val name: String,
    val description: String? = null,
)

@Serializable data class UpdateApplicationRequest(
    val name: String? = null,
    val description: String? = null,
    val accessType: String? = null,
    val redirectUris: List<String>? = null,
)

// -- Response DTOs ------------------------------------------------------------

@Serializable data class UserDto(
    val id: Int,
    val username: String,
    val email: String,
    val fullName: String,
    val emailVerified: Boolean,
    val enabled: Boolean,
    val mfaEnabled: Boolean,
)

@Serializable data class RoleDto(
    val id: Int,
    val name: String,
    val description: String?,
    val scope: String,
    val tenantId: Int,
)

@Serializable data class GroupDto(
    val id: Int,
    val name: String,
    val description: String?,
    val parentGroupId: Int?,
    val tenantId: Int,
)

@Serializable data class ApplicationDto(
    val id: Int,
    val clientId: String,
    val name: String,
    val description: String?,
    val accessType: String,
    val enabled: Boolean,
    val redirectUris: List<String>,
)

@Serializable data class SessionDto(
    val id: Int,
    val userId: Int?,
    val clientId: Int?,
    val scopes: String,
    val ipAddress: String?,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable data class AuditEventDto(
    val eventType: String,
    val userId: Int?,
    val clientId: Int?,
    val ipAddress: String?,
    val createdAt: String,
    val details: Map<String, String>,
)

// -- Domain → DTO mappers ----------------------------------------------------

internal fun com.kauth.domain.model.User.toApiDto() =
    UserDto(
        id = id!!.value,
        username = username,
        email = email,
        fullName = fullName,
        emailVerified = emailVerified,
        enabled = enabled,
        mfaEnabled = mfaEnabled,
    )

internal fun com.kauth.domain.model.Role.toApiDto() =
    RoleDto(
        id = id!!.value,
        name = name,
        description = description,
        scope = scope.name,
        tenantId = tenantId.value,
    )

internal fun com.kauth.domain.model.Group.toApiDto() =
    GroupDto(
        id = id!!.value,
        name = name,
        description = description,
        parentGroupId = parentGroupId?.value,
        tenantId = tenantId.value,
    )

internal fun com.kauth.domain.model.Application.toApiDto() =
    ApplicationDto(
        id = id.value,
        clientId = clientId,
        name = name,
        description = description,
        accessType = accessType.name.lowercase(),
        enabled = enabled,
        redirectUris = redirectUris,
    )

internal fun com.kauth.domain.model.Session.toApiDto() =
    SessionDto(
        id = id!!.value,
        userId = userId?.value,
        clientId = clientId?.value,
        scopes = scopes,
        ipAddress = ipAddress,
        createdAt = isoFormatter.format(createdAt),
        expiresAt = isoFormatter.format(expiresAt),
    )

internal fun com.kauth.domain.model.AuditEvent.toApiDto() =
    AuditEventDto(
        eventType = eventType.name,
        userId = userId?.value,
        clientId = clientId?.value,
        ipAddress = ipAddress,
        createdAt = isoFormatter.format(createdAt),
        details = details,
    )
