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
    private val tenantRepository      : TenantRepository,
    private val applicationRepository : ApplicationRepository,
    private val baseUrl               : String
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

            val portalClient = applicationRepository.findByClientId(tenant.id, PORTAL_CLIENT_ID)
                ?: continue  // V15 not yet applied — skip gracefully

            val callbackUri = "$baseUrl/t/${tenant.slug}/account/callback"
            // Only update when the URI has actually changed to avoid unnecessary writes
            if (portalClient.redirectUris != listOf(callbackUri)) {
                applicationRepository.update(
                    appId        = portalClient.id,
                    name         = portalClient.name,
                    description  = portalClient.description,
                    accessType   = portalClient.accessType.value,
                    redirectUris = listOf(callbackUri)
                )
            }
        }
    }
}
