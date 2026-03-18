package com.kauth.infrastructure

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
     * For each non-master tenant, find the portal client and ensure its
     * redirect URI is set to "{baseUrl}/t/{slug}/account/callback".
     *
     * Skips tenants whose portal client row is missing (should not happen
     * after V15 runs, but we guard defensively).
     */
    fun provisionRedirectUris() {
        val tenants = tenantRepository.findAll()
        for (tenant in tenants) {
            if (tenant.isMaster) continue

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
                )
            } else if (portalClient.redirectUris != listOf(callbackUri)) {
                // Only update when the URI has actually changed to avoid unnecessary writes
                applicationRepository.update(
                    appId = portalClient.id,
                    name = portalClient.name,
                    description = portalClient.description,
                    accessType = portalClient.accessType.value,
                    redirectUris = listOf(callbackUri),
                )
            }
        }
    }
}
