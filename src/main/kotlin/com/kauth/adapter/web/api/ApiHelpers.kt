package com.kauth.adapter.web.api

import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AttributeResult
import com.kauth.domain.service.ResourceServerError
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

/** Parses the `{userId}` path parameter, or replies 400 and invokes [bail] if it's missing/invalid. */
internal suspend inline fun ApplicationCall.parseUserIdOr(bail: () -> Nothing): UserId? {
    val raw = parameters["userId"]?.toIntOrNull()
    return if (raw == null) {
        respondProblem(HttpStatusCode.BadRequest, "Invalid user ID", "userId must be an integer.")
        bail()
    } else {
        UserId(raw)
    }
}

/** Parses the `{groupId}` path parameter, or replies 400 and invokes [bail] if it's missing/invalid. */
internal suspend inline fun ApplicationCall.parseGroupIdOr(bail: () -> Nothing): GroupId? {
    val raw = parameters["groupId"]?.toIntOrNull()
    return if (raw == null) {
        respondProblem(HttpStatusCode.BadRequest, "Invalid group ID", "groupId must be an integer.")
        bail()
    } else {
        GroupId(raw)
    }
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
        AdminError.SmtpRequired, AdminError.NoMethodsEnabled ->
            respondProblem(HttpStatusCode.UnprocessableEntity, "Validation Error", error.message)
    }

/** Maps the terminal [AttributeResult] failure variants to Problem+JSON responses. */
internal suspend fun ApplicationCall.respondAttributeError(result: AttributeResult<*>): Unit =
    when (result) {
        is AttributeResult.Success<*> ->
            respondProblem(
                HttpStatusCode.InternalServerError,
                "Unexpected Success",
                "Internal error: respondAttributeError called with Success.",
            )
        is AttributeResult.NotFound ->
            respondProblem(
                HttpStatusCode.NotFound,
                "Not Found",
                "No ${result.resource} matched the request.",
            )
        is AttributeResult.ValidationError ->
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "Validation Error",
                result.reason,
            )
        is AttributeResult.ReservedClaimName ->
            respondProblem(
                HttpStatusCode.BadRequest,
                "Reserved Claim Name",
                "The claim name '${result.claimName}' is reserved by OIDC/KotAuth and cannot be used. " +
                    "Use a custom prefix, e.g. 'custom:${result.claimName}'.",
            )
        is AttributeResult.DuplicateClaimName ->
            respondProblem(
                HttpStatusCode.Conflict,
                "Duplicate Claim Name",
                "Another attribute in this tenant already maps to claim name '${result.claimName}'.",
            )
        is AttributeResult.LimitReached ->
            respondProblem(
                HttpStatusCode.Conflict,
                "Mapper Limit Reached",
                "This tenant has reached the maximum of ${result.max} claim mappers.",
            )
    }

internal suspend fun ApplicationCall.respondResourceServerError(error: ResourceServerError): Unit =
    when (error) {
        is ResourceServerError.InvalidIdentifier ->
            respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid identifier", error.reason)
        ResourceServerError.InvalidName ->
            respondProblem(HttpStatusCode.UnprocessableEntity, "Validation Error", "Name cannot be blank.")
        ResourceServerError.IdentifierAlreadyExists ->
            respondProblem(
                HttpStatusCode.Conflict,
                "Conflict",
                "A resource server with this identifier already exists.",
            )
        ResourceServerError.NotFound ->
            respondProblem(HttpStatusCode.NotFound, "Not Found", "Resource server not found.")
        ResourceServerError.CrossTenant ->
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "Cross-tenant",
                "Resource does not belong to this workspace.",
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

@Serializable data class CreateApplicationRequest(
    val clientId: String,
    val name: String,
    val description: String? = null,
    val accessType: String = "public",
    val redirectUris: List<String>,
)

@Serializable data class UpdateApplicationRequest(
    val name: String? = null,
    val description: String? = null,
    val accessType: String? = null,
    val redirectUris: List<String>? = null,
    val audience: String? = null,
)

@Serializable data class SetDefaultRolesRequest(
    val roleIds: List<Int>,
)

@Serializable data class UpsertUserAttributeRequest(
    val value: String,
)

/**
 * Invite a new user — no password up front. The user will receive an email
 * with an invite link and set their own password on first login.
 * Requires SMTP to be configured on the tenant.
 */
@Serializable data class InviteUserRequest(
    val username: String,
    val email: String,
    val fullName: String,
)

/**
 * Response for admin-triggered temporary-password creation. The raw link is
 * returned exactly once — callers MUST treat it as a secret and never persist
 * or log it. Expires in 24 hours.
 */
@Serializable data class TemporaryPasswordResponse(
    /** Full `/t/{slug}/change-password?token=...` URL to share with the user. */
    val changePasswordUrl: String,
    /** ISO-8601 expiry timestamp (24 hours from issuance). */
    val expiresAt: String,
)

@Serializable data class UpsertClaimMapperRequest(
    val claimName: String,
    val includeInAccess: Boolean = true,
    val includeInId: Boolean = false,
)

@Serializable data class CreateWebhookRequest(
    val url: String,
    val description: String = "",
    val events: List<String>,
)

@Serializable data class CreateResourceServerRequest(
    val identifier: String,
    val name: String,
    val description: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable data class UpdateResourceServerRequest(
    val name: String,
    val description: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable data class SetAuthorizedResourceServersRequest(
    val resourceServerIds: List<Int>,
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
    val requiredActions: List<String>,
    val isLocked: Boolean,
    val createdAt: String? = null,
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
    val audience: String? = null,
)

/**
 * Response body for `POST /applications`. `clientSecret` is the raw secret and
 * is returned exactly once, present only when `application.accessType ==
 * "confidential"`. Callers MUST persist it before the response is discarded;
 * there is no way to retrieve it again.
 */
@Serializable data class CreateApplicationResponse(
    val application: ApplicationDto,
    val clientSecret: String? = null,
)

/**
 * Response body for `POST /applications/{id}/regenerate-secret`. The
 * `clientSecret` field is the raw secret and is returned exactly once —
 * callers MUST persist it before the response is discarded; there is no way
 * to retrieve it again.
 */
@Serializable data class ClientSecretResponse(
    val clientSecret: String,
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

@Serializable data class UserAttributesDto(
    /** Flat key→value map. Empty if the user has no custom attributes. */
    val attributes: Map<String, String>,
)

@Serializable data class ClaimMapperDto(
    val attributeKey: String,
    val claimName: String,
    val includeInAccess: Boolean,
    val includeInId: Boolean,
)

@Serializable data class ClaimMappersDto(
    val mappers: List<ClaimMapperDto>,
)

/** Response for `POST /users/{id}/revoke-sessions` — count of sessions revoked (0 if the user had none). */
@Serializable data class RevokeSessionsResponse(
    val revoked: Int,
)

/**
 * Read-only workspace configuration: tenant metadata, enabled sign-in methods, security/password
 * policy, MFA policy, and magic-link/email-OTP limits. SMTP credentials are NEVER included — SMTP
 * is configured and inspected exclusively via the admin UI. Not editable via API in v1.21.0.
 */
@Serializable data class WorkspaceDto(
    val id: Int,
    val slug: String,
    val displayName: String,
    val issuerUrl: String? = null,
    val tokenExpirySeconds: Long,
    val refreshTokenExpirySeconds: Long,
    val registrationEnabled: Boolean,
    val emailVerificationRequired: Boolean,
    val passkeysEnabled: Boolean,
    val maxConcurrentSessions: Int? = null,
    val signInMethods: WorkspaceSignInMethodsDto,
    val passwordPolicy: WorkspacePasswordPolicyDto,
    val mfaPolicy: String,
    val lockoutMaxAttempts: Int,
    val lockoutDurationMinutes: Int,
    val magicLinkTtlMinutes: Int,
    val emailOtpSignupEnabled: Boolean,
    val emailOtpLockoutThreshold: Int,
    val corsAllowCredentials: Boolean,
    val portalLayout: String,
)

@Serializable data class WorkspaceSignInMethodsDto(
    val password: Boolean,
    val passkey: Boolean,
    val magicLink: Boolean,
    val emailOtp: Boolean,
)

@Serializable data class WorkspacePasswordPolicyDto(
    val minLength: Int,
    val requireSpecial: Boolean,
    val requireUppercase: Boolean,
    val requireNumber: Boolean,
    val historyCount: Int,
    val maxAgeDays: Int,
    val blacklistEnabled: Boolean,
    val hibpCheckEnabled: Boolean,
)

@Serializable data class WebhookEndpointDto(
    val id: Int,
    val url: String,
    val description: String,
    val events: List<String>,
    val enabled: Boolean,
    val createdAt: String,
)

/**
 * Response for POST /webhooks. Contains the created endpoint metadata plus
 * the HMAC signing secret in plaintext. The secret is returned exactly
 * ONCE — the server stores it and uses it to sign delivery payloads;
 * clients that need to verify incoming webhooks must persist it now.
 */
@Serializable data class CreateWebhookResponse(
    val endpoint: WebhookEndpointDto,
    val secret: String,
)

@Serializable data class ResourceServerDto(
    val id: Int,
    val identifier: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    val scopes: List<String>,
    val createdAt: String,
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
        requiredActions = requiredActions.map { it.name },
        isLocked = lockedUntil?.isAfter(java.time.Instant.now()) == true,
        createdAt = createdAt?.let { isoFormatter.format(it) },
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
        audience = audience,
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

internal fun com.kauth.domain.model.TenantClaimMapper.toApiDto() =
    ClaimMapperDto(
        attributeKey = attributeKey,
        claimName = claimName,
        includeInAccess = includeInAccess,
        includeInId = includeInId,
    )

internal fun com.kauth.domain.model.WebhookEndpoint.toApiDto(): WebhookEndpointDto =
    WebhookEndpointDto(
        id = id!!,
        url = url,
        description = description,
        events = events.map { it.value }.sorted(),
        enabled = enabled,
        createdAt = isoFormatter.format(createdAt),
    )

internal fun com.kauth.domain.model.ResourceServer.toApiDto(): ResourceServerDto =
    ResourceServerDto(
        id = id!!.value,
        identifier = identifier,
        name = name,
        description = description,
        enabled = enabled,
        scopes = scopes,
        createdAt = isoFormatter.format(createdAt),
    )

internal fun com.kauth.domain.model.Tenant.toWorkspaceApiDto(): WorkspaceDto {
    val sc = securityConfig
    return WorkspaceDto(
        id = id.value,
        slug = slug,
        displayName = displayName,
        issuerUrl = issuerUrl,
        tokenExpirySeconds = tokenExpirySeconds,
        refreshTokenExpirySeconds = refreshTokenExpirySeconds,
        registrationEnabled = registrationEnabled,
        emailVerificationRequired = emailVerificationRequired,
        passkeysEnabled = passkeysEnabled,
        maxConcurrentSessions = maxConcurrentSessions,
        signInMethods =
            WorkspaceSignInMethodsDto(
                password = sc.passwordLoginEnabled,
                passkey = passkeysEnabled,
                magicLink = sc.magicLinkEnabled,
                emailOtp = sc.emailOtpLoginEnabled,
            ),
        passwordPolicy =
            WorkspacePasswordPolicyDto(
                minLength = sc.passwordMinLength,
                requireSpecial = sc.passwordRequireSpecial,
                requireUppercase = sc.passwordRequireUppercase,
                requireNumber = sc.passwordRequireNumber,
                historyCount = sc.passwordHistoryCount,
                maxAgeDays = sc.passwordMaxAgeDays,
                blacklistEnabled = sc.passwordBlacklistEnabled,
                hibpCheckEnabled = sc.hibpCheckEnabled,
            ),
        mfaPolicy = sc.mfaPolicy,
        lockoutMaxAttempts = sc.lockoutMaxAttempts,
        lockoutDurationMinutes = sc.lockoutDurationMinutes,
        magicLinkTtlMinutes = sc.magicLinkTokenTtlMinutes,
        emailOtpSignupEnabled = sc.emailOtpSignupEnabled,
        emailOtpLockoutThreshold = sc.emailOtpLockoutThreshold,
        corsAllowCredentials = sc.corsAllowCredentials,
        portalLayout = portalConfig.layout.name,
    )
}
