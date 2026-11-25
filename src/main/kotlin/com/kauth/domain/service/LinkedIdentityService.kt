package com.kauth.domain.service

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.FactorChange
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.SocialAccountRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.port.WebAuthnCredentialRepository

sealed interface UnlinkResult {
    data object Unlinked : UnlinkResult

    data object NotLinked : UnlinkResult

    /** Self-service only: the link is the user's last way in, so removing it would lock them out. */
    data object OnlyWayToSignIn : UnlinkResult
}

/** Removes the link between a user and an identity provider account. */
class LinkedIdentityService(
    private val socialAccounts: SocialAccountRepository,
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val identityProviders: IdentityProviderRepository,
    private val passkeys: WebAuthnCredentialRepository,
    private val auditLog: AuditLogPort,
    private val factorChangeNotifier: FactorChangeNotifier? = null,
) {
    fun linkedAccounts(userId: UserId): List<SocialAccount> = socialAccounts.findByUserId(userId)

    /**
     * True when [provider] is the only way [userId] can still sign in. An administrator may unlink it
     * anyway, since they can set a temporary password afterwards; the user may not.
     */
    fun isOnlyWayToSignIn(
        tenantId: TenantId,
        userId: UserId,
        provider: ProviderKey,
    ): Boolean {
        val tenant = tenants.findById(tenantId) ?: return false
        val user = users.findById(userId, tenantId) ?: return false
        val others = socialAccounts.findByUserId(userId).filter { it.provider != provider }
        return !hasOtherWayIn(tenant, user, others)
    }

    /** The user removes one of their own links, from the account portal. */
    fun unlinkOwn(
        tenantId: TenantId,
        userId: UserId,
        provider: ProviderKey,
        ipAddress: String?,
    ): UnlinkResult {
        val link = linkOf(userId, provider) ?: return UnlinkResult.NotLinked
        if (isOnlyWayToSignIn(tenantId, userId, provider)) return UnlinkResult.OnlyWayToSignIn
        socialAccounts.delete(userId, provider)
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = userId,
                clientId = null,
                eventType = AuditEventType.IDENTITY_UNLINKED,
                ipAddress = ipAddress,
                userAgent = null,
                details = details(link),
            ),
        )
        factorChangeNotifier?.notify(userId, tenantId, FactorChange.IDENTITY_UNLINKED)
        return UnlinkResult.Unlinked
    }

    /** An administrator removes a user's link, from the console. */
    fun unlinkByAdmin(
        tenantId: TenantId,
        userId: UserId,
        provider: ProviderKey,
        actorId: UserId,
        ipAddress: String?,
    ): UnlinkResult {
        users.findById(userId, tenantId) ?: return UnlinkResult.NotLinked
        val link = linkOf(userId, provider) ?: return UnlinkResult.NotLinked
        socialAccounts.delete(userId, provider)
        auditLog.record(
            AuditEvent(
                tenantId = tenantId,
                userId = actorId,
                clientId = null,
                eventType = AuditEventType.ADMIN_IDENTITY_UNLINKED,
                ipAddress = ipAddress,
                userAgent = null,
                details = details(link) + ("targetUser" to userId.value.toString()),
            ),
        )
        factorChangeNotifier?.notify(userId, tenantId, FactorChange.IDENTITY_UNLINKED_BY_ADMIN)
        return UnlinkResult.Unlinked
    }

    private fun linkOf(
        userId: UserId,
        provider: ProviderKey,
    ): SocialAccount? = socialAccounts.findByUserId(userId).find { it.provider == provider }

    private fun details(link: SocialAccount): Map<String, String> =
        mapOf("provider" to link.provider.value, "providerUserId" to link.providerUserId)

    private fun hasOtherWayIn(
        tenant: Tenant,
        user: User,
        otherLinks: List<SocialAccount>,
    ): Boolean {
        val security = tenant.securityConfig
        if (security.passwordLoginEnabled && user.passwordHash != User.SENTINEL_PASSWORD_HASH) return true
        if (security.magicLinkEnabled || security.emailOtpLoginEnabled) return true
        if (tenant.passkeysEnabled && passkeys.findByUserId(user.id!!, tenant.id).isNotEmpty()) return true
        if (otherLinks.isEmpty()) return false
        val usable = identityProviders.findEnabledByTenant(tenant.id).map { it.provider }.toSet()
        return otherLinks.any { it.provider in usable }
    }
}
