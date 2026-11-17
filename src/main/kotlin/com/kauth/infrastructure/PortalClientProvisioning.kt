package com.kauth.infrastructure

import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.TenantRepository

/**
 * Startup task: ensures every non-master tenant has a 'kotauth-portal' client
 * with a redirect URI that points to this instance's base URL.
 *
 * The DB migration (V15) inserts the client row, but it cannot know the
 * redirect URI at migration time because that depends on KAUTH_BASE_URL.
 * This service bridges that gap at startup, making the provisioning idempotent.
 *
 * Calling [provisionRedirectUris] on every startup is safe — it uses
 * ApplicationRepository.update() which is a full replace, so stale URIs
 * from a previous deployment (different base URL) are automatically corrected.
 */
class PortalClientProvisioning(
    private val tenantRepository: TenantRepository,
    private val applicationRepository: ApplicationRepository,
    private val baseUrl: String,
) {
    companion object {
        const val PORTAL_CLIENT_ID = "kotauth-portal"
    }

    /**
     * For each non-master tenant, ensure the portal client exists and its
     * redirect URI is "{baseUrl}/t/{slug}/account/callback".
     */
    fun provisionRedirectUris() {
        tenantRepository.findAll().forEach(::provisionForTenant)
    }

    /** Single-tenant variant of [provisionRedirectUris]; master has no portal client. */
    fun provisionForTenant(tenant: Tenant) {
        if (tenant.isMaster) return

        val callbackUri = "$baseUrl/t/${tenant.slug}/account/callback"
        val portalClient = applicationRepository.findByClientId(tenant.id, PORTAL_CLIENT_ID)

        if (portalClient == null) {
            // Workspace was created after V15 ran — the migration only seeds existing
            // tenants, so new tenants have no portal client row yet. Create it now
            // with the correct redirect URI already set.
            applicationRepository.create(
                tenantId = tenant.id,
                clientId = PORTAL_CLIENT_ID,
                name = "KotAuth Self-Service Portal",
                description = "Built-in client for the tenant self-service portal (profile, password, MFA)",
                accessType = "public",
                redirectUris = listOf(callbackUri),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
                clientSecretHash = null,
                audience = null,
            )
        } else if (portalClient.redirectUris != listOf(callbackUri)) {
            // Only update when the URI has actually changed to avoid unnecessary writes. update() is a
            // full replace, so everything but the URI is passed through: an operator may have narrowed
            // the grants or set an audience or launcher tile, and a backup restores them.
            applicationRepository.update(
                appId = portalClient.id,
                name = portalClient.name,
                description = portalClient.description,
                accessType = portalClient.accessType.value,
                redirectUris = listOf(callbackUri),
                grantTypes = portalClient.grantTypes,
                launcherUrl = portalClient.launcherUrl,
                iconUrl = portalClient.iconUrl,
                launcherVisible = portalClient.launcherVisible,
                launcherDisplayOrder = portalClient.launcherDisplayOrder,
                audience = portalClient.audience,
                tokenExpiryOverride = portalClient.tokenExpiryOverride,
            )
        }
    }
}
