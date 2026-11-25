package com.kauth.domain.service

import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.OidcScopes
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserConsent
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.ConsentRepository
import com.kauth.domain.port.ResourceServerRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.UserRepository
import java.time.Clock

/** One line of the consent screen. [apiName] is null for the OpenID Connect scopes. */
data class ConsentScope(
    val scope: String,
    val apiName: String? = null,
    val description: String? = null,
) {
    val isIdentity: Boolean get() = scope in OidcScopes.IDENTITY
}

/** Everything the consent screen shows. */
data class ConsentRequest(
    val application: Application,
    val user: User,
    val scopes: List<ConsentScope>,
)

data class ConsentedApplication(
    val application: Application,
    val consent: UserConsent,
)

/**
 * Asks users before an application gets a code for them, and remembers what they allowed.
 * An application only asks when its `consentRequired` switch is on.
 */
class ConsentService(
    private val consents: ConsentRepository,
    private val applications: ApplicationRepository,
    private val sessions: SessionRepository,
    private val users: UserRepository,
    private val auditLog: AuditLogPort,
    private val resourceServers: ResourceServerRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** True when [userId] must see the consent screen before [client] gets a code for [scope]. */
    fun isRequired(
        tenantId: TenantId,
        userId: UserId,
        client: Application,
        scope: String,
        force: Boolean = false,
    ): Boolean {
        if (!client.consentRequired || isBuiltIn(client.clientId)) return false
        if (force) return true
        val granted = consents.find(tenantId, userId, client.id)?.scopes ?: return true
        return !granted.containsAll(scopesOf(scope))
    }

    /** Null when the application or the user is gone, or the application is disabled. */
    fun request(
        tenantId: TenantId,
        userId: UserId,
        clientId: String,
        scope: String,
    ): ConsentRequest? {
        val client = applications.findByClientId(tenantId, clientId)?.takeIf { it.enabled } ?: return null
        val user = users.findById(userId, tenantId) ?: return null
        return ConsentRequest(client, user, describe(client, scope))
    }

    /** The scopes [client] asks for, described for the consent screen, in the order requested. */
    fun describe(
        client: Application,
        scope: String,
    ): List<ConsentScope> {
        val apis = resourceServers?.listAuthorizedFor(client.id).orEmpty()
        return scopesOf(scope).map { requested ->
            if (requested in OidcScopes.IDENTITY) return@map ConsentScope(requested)
            val api = apis.firstOrNull { requested in it.scopes }
            ConsentScope(
                scope = requested,
                apiName = api?.name,
                description = api?.scopeDescriptions?.get(requested)?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun grant(
        tenantId: TenantId,
        userId: UserId,
        client: Application,
        scope: String,
        ipAddress: String?,
    ): UserConsent {
        val requested = scopesOf(scope)
        val existing = consents.find(tenantId, userId, client.id)?.scopes.orEmpty()
        val saved = consents.save(tenantId, userId, client.id, existing + requested, clock.instant())
        record(tenantId, userId, client.id, AuditEventType.CONSENT_GRANTED, ipAddress, requested)
        return saved
    }

    fun deny(
        tenantId: TenantId,
        userId: UserId,
        client: Application,
        scope: String,
        ipAddress: String?,
    ) = record(tenantId, userId, client.id, AuditEventType.CONSENT_DENIED, ipAddress, scopesOf(scope))

    /** The applications [userId] has allowed, skipping any since deleted. */
    fun grantsFor(
        tenantId: TenantId,
        userId: UserId,
    ): List<ConsentedApplication> =
        consents
            .findByUser(tenantId, userId)
            .mapNotNull { consent ->
                applications
                    .findById(consent.applicationId)
                    ?.takeIf { it.tenantId == tenantId }
                    ?.let { ConsentedApplication(it, consent) }
            }.sortedBy { it.application.name.lowercase() }

    /** The user withdraws an application's access, from the account portal. */
    fun revokeOwn(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        ipAddress: String?,
    ): Boolean = revoke(tenantId, userId, applicationId, ipAddress, AuditEventType.CONSENT_REVOKED, actorId = null)

    /** An administrator withdraws an application's access for a user, from the console. */
    fun revokeByAdmin(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        actorId: UserId?,
        ipAddress: String?,
    ): Boolean = revoke(tenantId, userId, applicationId, ipAddress, AuditEventType.ADMIN_CONSENT_REVOKED, actorId)

    // Revoking also ends the application's sessions, so its refresh tokens stop working with the grant.
    private fun revoke(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        ipAddress: String?,
        type: AuditEventType,
        actorId: UserId?,
    ): Boolean {
        val consent = consents.find(tenantId, userId, applicationId) ?: return false
        consents.delete(tenantId, userId, applicationId)
        val now = clock.instant()
        sessions
            .findActiveByTenant(tenantId, userId = userId, applicationId = applicationId)
            .forEach { session -> session.id?.let { sessions.revoke(it, now, REVOCATION_REASON) } }
        val details =
            buildMap {
                put("scopes", consent.scopes.sorted().joinToString(" "))
                if (actorId != null) put("targetUser", userId.value.toString())
            }
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = actorId ?: userId,
                clientId = applicationId,
                eventType = type,
                ipAddress = ipAddress,
                userAgent = null,
                details = details,
            ),
        )
        return true
    }

    private fun record(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        type: AuditEventType,
        ipAddress: String?,
        scopes: Set<String>,
    ) = auditLog.record(
        AuditEvent(
            tenantId = tenantId,
            userId = userId,
            clientId = applicationId,
            eventType = type,
            ipAddress = ipAddress,
            userAgent = null,
            details = mapOf("scopes" to scopes.joinToString(" ")),
        ),
    )

    companion object {
        const val REVOCATION_REASON = "consent_revoked"

        // Kotauth's own account portal and admin console sign users in to Kotauth itself.
        private val BUILT_IN_CLIENTS = setOf("kotauth-portal", "kotauth-admin")

        fun isBuiltIn(clientId: String): Boolean = clientId in BUILT_IN_CLIENTS

        fun scopesOf(scope: String): Set<String> =
            scope.split(' ').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf()).ifEmpty {
                linkedSetOf("openid")
            }
    }
}
