package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.SmtpPatch
import com.kauth.domain.model.WorkspacePatch
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.WorkspacePatchService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

internal fun Route.apiWorkspaceRoutes(
    tenantRepository: TenantRepository,
    workspacePatchService: WorkspacePatchService?,
) {
    route("/workspace") {
        get {
            requireScope(call, ApiScope.WORKSPACE_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val tenant =
                tenantRepository.findById(tenantId)
                    ?: return@get call.respondProblem(
                        HttpStatusCode.NotFound,
                        "Workspace not found",
                        "No workspace resolved for tenant id ${tenantId.value}.",
                    )
            call.respond(HttpStatusCode.OK, tenant.toWorkspaceApiDto())
        }

        if (workspacePatchService != null) {
            patch {
                requireScope(call, ApiScope.WORKSPACE_WRITE) ?: return@patch
                val body =
                    try {
                        call.receive<UpdateWorkspaceRequest>()
                    } catch (e: BadRequestException) {
                        return@patch call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid request body",
                            e.cause?.message ?: e.message ?: "The body is not a valid workspace update.",
                        )
                    }
                val patch =
                    body.toDomain()
                        ?: return@patch call.respondProblem(
                            HttpStatusCode.UnprocessableEntity,
                            "Validation Error",
                            "loginIdentifierMode must be one of: " +
                                LoginIdentifierMode.entries.joinToString(", ") { it.name } + ".",
                        )
                when (val result = workspacePatchService.patch(call.attributes[TenantIdAttr], patch)) {
                    is AdminResult.Success -> call.respond(HttpStatusCode.OK, result.value.toWorkspaceApiDto())
                    is AdminResult.Failure -> call.respondAdminError(result.error)
                }
            }
        }
    }
}

/** Body of `PATCH /workspace`. Every field is optional; an absent field keeps its current value. */
@Serializable
internal data class UpdateWorkspaceRequest(
    val displayName: String? = null,
    val issuerUrl: String? = null,
    /** The workspace's own origin, e.g. `https://auth.product.com`; an empty string clears it. */
    val publicBaseUrl: String? = null,
    val tokenExpirySeconds: Long? = null,
    val refreshTokenExpirySeconds: Long? = null,
    val registrationEnabled: Boolean? = null,
    val emailVerificationRequired: Boolean? = null,
    val signInMethods: UpdateSignInMethodsRequest? = null,
    val passwordPolicy: UpdatePasswordPolicyRequest? = null,
    val mfaPolicy: String? = null,
    val lockoutMaxAttempts: Int? = null,
    val lockoutDurationMinutes: Int? = null,
    val magicLinkTtlMinutes: Int? = null,
    val emailOtpSignupEnabled: Boolean? = null,
    val emailOtpLockoutThreshold: Int? = null,
    val corsAllowCredentials: Boolean? = null,
    val loginIdentifierMode: String? = null,
    val usernameChangeEnabled: Boolean? = null,
    val defaultLocale: String? = null,
    val smtp: UpdateSmtpRequest? = null,
    /** Active sessions a user may hold in one application; 0 removes the limit. */
    val maxConcurrentSessions: Int? = null,
) {
    /** Null when [loginIdentifierMode] names no known mode. */
    fun toDomain(): WorkspacePatch? {
        val mode =
            loginIdentifierMode?.let { LoginIdentifierMode.parseOrNull(it) ?: return null }
        return WorkspacePatch(
            displayName = displayName,
            issuerUrl = issuerUrl,
            publicBaseUrl = publicBaseUrl,
            tokenExpirySeconds = tokenExpirySeconds,
            refreshTokenExpirySeconds = refreshTokenExpirySeconds,
            registrationEnabled = registrationEnabled,
            emailVerificationRequired = emailVerificationRequired,
            passwordLoginEnabled = signInMethods?.password,
            passkeysEnabled = signInMethods?.passkey,
            magicLinkEnabled = signInMethods?.magicLink,
            emailOtpLoginEnabled = signInMethods?.emailOtp,
            passwordMinLength = passwordPolicy?.minLength,
            passwordRequireSpecial = passwordPolicy?.requireSpecial,
            passwordRequireUppercase = passwordPolicy?.requireUppercase,
            passwordRequireNumber = passwordPolicy?.requireNumber,
            passwordHistoryCount = passwordPolicy?.historyCount,
            passwordMaxAgeDays = passwordPolicy?.maxAgeDays,
            passwordBlacklistEnabled = passwordPolicy?.blacklistEnabled,
            hibpCheckEnabled = passwordPolicy?.hibpCheckEnabled,
            mfaPolicy = mfaPolicy,
            lockoutMaxAttempts = lockoutMaxAttempts,
            lockoutDurationMinutes = lockoutDurationMinutes,
            magicLinkTokenTtlMinutes = magicLinkTtlMinutes,
            emailOtpSignupEnabled = emailOtpSignupEnabled,
            emailOtpLockoutThreshold = emailOtpLockoutThreshold,
            corsAllowCredentials = corsAllowCredentials,
            loginIdentifierMode = mode,
            usernameChangeEnabled = usernameChangeEnabled,
            defaultLocale = defaultLocale,
            smtp = smtp?.toDomain(),
            maxConcurrentSessions = maxConcurrentSessions,
        )
    }
}

@Serializable
internal data class UpdateSignInMethodsRequest(
    val password: Boolean? = null,
    val passkey: Boolean? = null,
    val magicLink: Boolean? = null,
    val emailOtp: Boolean? = null,
)

@Serializable
internal data class UpdatePasswordPolicyRequest(
    val minLength: Int? = null,
    val requireSpecial: Boolean? = null,
    val requireUppercase: Boolean? = null,
    val requireNumber: Boolean? = null,
    val historyCount: Int? = null,
    val maxAgeDays: Int? = null,
    val blacklistEnabled: Boolean? = null,
    val hibpCheckEnabled: Boolean? = null,
)

/** `password` is write-only: it is never returned, and an absent or blank value keeps the stored one. */
@Serializable
internal data class UpdateSmtpRequest(
    val enabled: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val fromAddress: String? = null,
    val fromName: String? = null,
    val tlsEnabled: Boolean? = null,
) {
    fun toDomain(): SmtpPatch = SmtpPatch(enabled, host, port, username, password, fromAddress, fromName, tlsEnabled)
}
