package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.CorsPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.util.SecureTokens

/** A newly created application plus its one-time plaintext secret, if one was issued. */
data class CreatedApplication(
    val application: Application,
    val plaintextSecret: String?,
)

class ApplicationManagementService(
    private val applicationRepository: ApplicationRepository,
    private val tenantRepository: TenantRepository,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val corsPort: CorsPort? = null,
) {
    fun createApplication(
        tenantId: TenantId,
        clientId: String,
        name: String,
        description: String?,
        accessType: String,
        redirectUris: List<String>,
        grantTypes: Set<GrantType>,
        audience: String? = null,
    ): AdminResult<CreatedApplication> {
        if (clientId.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Client ID is required."))
        }
        if (!clientId.matches(Regex("[a-z0-9-]+"))) {
            return AdminResult.Failure(
                AdminError.Validation("Client ID may only contain lowercase letters, numbers, and hyphens."),
            )
        }
        if (name.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Name is required."))
        }

        val resolvedAccessType = AccessType.fromValue(accessType)

        if (grantTypes.isEmpty() && resolvedAccessType != AccessType.BEARER_ONLY) {
            return AdminResult.Failure(
                AdminError.Validation("Select at least one grant type this application will use."),
            )
        }
        if (resolvedAccessType == AccessType.BEARER_ONLY && grantTypes.isNotEmpty()) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "Bearer-only applications validate tokens and never initiate a flow, so they " +
                        "cannot be registered for any grant type. Clear the selected grants, or change " +
                        "the access type to public or confidential.",
                ),
            )
        }
        if (GrantType.CLIENT_CREDENTIALS in grantTypes && resolvedAccessType != AccessType.CONFIDENTIAL) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "The client credentials grant requires a confidential application, " +
                        "because it authenticates with a client secret.",
                ),
            )
        }
        if (GrantType.AUTHORIZATION_CODE in grantTypes && redirectUris.isEmpty()) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "At least one redirect URI is required. The authorization code flow " +
                        "needs a registered URI to bind to.",
                ),
            )
        }
        val trimmedAudience = audience?.trim()?.takeIf { it.isNotBlank() }
        if (trimmedAudience != null && trimmedAudience.length > 200) {
            return AdminResult.Failure(AdminError.Validation("Token audience must be 200 characters or fewer."))
        }
        if (applicationRepository.existsByClientId(tenantId, clientId)) {
            return AdminResult.Failure(AdminError.Conflict("Client ID '$clientId' already exists."))
        }

        val secret = if (resolvedAccessType == AccessType.CONFIDENTIAL) SecureTokens.randomBase64Url(32) else null

        val created =
            applicationRepository.create(
                tenantId = tenantId,
                clientId = clientId,
                name = name,
                description = description,
                accessType = accessType,
                redirectUris = redirectUris,
                grantTypes = grantTypes,
                clientSecretHash = secret?.let { passwordHasher.hash(it) },
                audience = trimmedAudience,
            )

        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = created.id,
                eventType = AuditEventType.ADMIN_CLIENT_CREATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to created.clientId),
            ),
        )

        return AdminResult.Success(CreatedApplication(created, secret))
    }

    fun updateApplication(
        appId: ApplicationId,
        tenantId: TenantId,
        name: String? = null,
        description: String? = null,
        accessType: String? = null,
        redirectUris: List<String>? = null,
        grantTypes: Set<GrantType>? = null,
        launcherUrl: String? = null,
        iconUrl: String? = null,
        launcherVisible: Boolean? = null,
        launcherDisplayOrder: Int? = null,
        audience: String? = null,
    ): AdminResult<Application> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        val resolvedName = name?.trim() ?: app.name
        val resolvedDescription =
            if (description != null) description.trim().takeIf { it.isNotBlank() } else app.description
        val resolvedAccessType = accessType ?: app.accessType.value
        val resolvedRedirectUris = redirectUris ?: app.redirectUris
        val resolvedGrantTypes = grantTypes ?: app.grantTypes
        val resolvedLauncherUrl =
            if (launcherUrl != null) launcherUrl.trim().takeIf { it.isNotBlank() } else app.launcherUrl
        val resolvedIconUrl =
            if (iconUrl != null) iconUrl.trim().takeIf { it.isNotBlank() } else app.iconUrl
        val resolvedLauncherVisible = launcherVisible ?: app.launcherVisible
        val resolvedLauncherDisplayOrder = launcherDisplayOrder ?: app.launcherDisplayOrder
        val resolvedAudience =
            if (audience != null) audience.trim().takeIf { it.isNotBlank() } else app.audience

        if (resolvedName.isBlank()) {
            return AdminResult.Failure(AdminError.Validation("Name is required."))
        }

        val resolvedAccessTypeEnum = AccessType.fromValue(resolvedAccessType)

        if (resolvedGrantTypes.isEmpty() && resolvedAccessTypeEnum != AccessType.BEARER_ONLY) {
            return AdminResult.Failure(
                AdminError.Validation("Select at least one grant type this application will use."),
            )
        }
        if (resolvedAccessTypeEnum == AccessType.BEARER_ONLY && resolvedGrantTypes.isNotEmpty()) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "Bearer-only applications validate tokens and never initiate a flow, so they " +
                        "cannot be registered for any grant type. Clear the selected grants, or change " +
                        "the access type to public or confidential.",
                ),
            )
        }
        if (GrantType.CLIENT_CREDENTIALS in resolvedGrantTypes && resolvedAccessTypeEnum != AccessType.CONFIDENTIAL) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "The client credentials grant requires a confidential application, " +
                        "because it authenticates with a client secret.",
                ),
            )
        }
        if (GrantType.AUTHORIZATION_CODE in resolvedGrantTypes && resolvedRedirectUris.isEmpty()) {
            return AdminResult.Failure(
                AdminError.Validation(
                    "At least one redirect URI is required. The authorization code flow " +
                        "(including email-OTP back-channel exchange) needs a registered URI to bind to.",
                ),
            )
        }
        if (resolvedAudience != null && resolvedAudience.length > 200) {
            return AdminResult.Failure(AdminError.Validation("Token audience must be 200 characters or fewer."))
        }

        if (resolvedLauncherUrl != null) {
            val launcherOrigin =
                extractHttpOrigin(resolvedLauncherUrl)
                    ?: return AdminResult.Failure(
                        AdminError.Validation("Launcher URL must be a valid http or https URL."),
                    )
            val redirectOrigins = resolvedRedirectUris.mapNotNull(::extractHttpOrigin).toSet()
            if (redirectOrigins.isEmpty()) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Add at least one redirect URI on the same origin before setting a launcher URL.",
                    ),
                )
            }
            if (launcherOrigin !in redirectOrigins) {
                return AdminResult.Failure(
                    AdminError.Validation(
                        "Launcher URL origin '$launcherOrigin' must match one of the registered redirect URI origins.",
                    ),
                )
            }
        }

        if (resolvedIconUrl != null && extractHttpOrigin(resolvedIconUrl) == null) {
            return AdminResult.Failure(AdminError.Validation("Icon URL must be a valid http or https URL."))
        }

        val updated =
            applicationRepository.update(
                appId = appId,
                name = resolvedName,
                description = resolvedDescription,
                accessType = resolvedAccessType,
                redirectUris = resolvedRedirectUris,
                grantTypes = resolvedGrantTypes,
                launcherUrl = resolvedLauncherUrl,
                iconUrl = resolvedIconUrl,
                launcherVisible = resolvedLauncherVisible,
                launcherDisplayOrder = resolvedLauncherDisplayOrder,
                audience = resolvedAudience,
            )

        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = AuditEventType.ADMIN_CLIENT_UPDATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId),
            ),
        )

        return AdminResult.Success(updated)
    }

    fun setApplicationEnabled(
        appId: ApplicationId,
        tenantId: TenantId,
        enabled: Boolean,
    ): AdminResult<Unit> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        applicationRepository.setEnabled(appId, enabled)
        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = if (enabled) AuditEventType.ADMIN_CLIENT_ENABLED else AuditEventType.ADMIN_CLIENT_DISABLED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId, "enabled" to enabled.toString()),
            ),
        )

        return AdminResult.Success(Unit)
    }

    fun regenerateClientSecret(
        appId: ApplicationId,
        tenantId: TenantId,
    ): AdminResult<String> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        val secret = SecureTokens.randomBase64Url(32)
        applicationRepository.setClientSecretHash(appId, passwordHasher.hash(secret))

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId),
            ),
        )

        return AdminResult.Success(secret)
    }

    fun deleteApplication(
        appId: ApplicationId,
        tenantId: TenantId,
    ): AdminResult<Unit> {
        val app =
            applicationRepository.findById(appId)
                ?: return AdminResult.Failure(AdminError.NotFound("Application not found."))

        if (app.tenantId != tenantId) {
            return AdminResult.Failure(AdminError.NotFound("Application not found in this workspace."))
        }

        val deleted = applicationRepository.softDelete(appId)
        if (!deleted) {
            // Race: another actor deleted it between findById and softDelete, OR
            // findById returned a row but the ID was already flipped elsewhere.
            // Treat as idempotent success — the caller's goal state is satisfied.
            return AdminResult.Success(Unit)
        }

        invalidateCors(tenantId)

        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = null,
                clientId = appId,
                eventType = AuditEventType.ADMIN_CLIENT_DELETED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("clientId" to app.clientId),
            ),
        )

        return AdminResult.Success(Unit)
    }

    private fun invalidateCors(tenantId: TenantId) {
        val port = corsPort ?: return
        tenantRepository.findById(tenantId)?.slug?.let(port::invalidate)
    }

    private fun extractHttpOrigin(url: String): String? =
        try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme != "http" && scheme != "https") {
                null
            } else if (host.isNullOrBlank()) {
                null
            } else {
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$port"
            }
        } catch (_: Exception) {
            null
        }
}
