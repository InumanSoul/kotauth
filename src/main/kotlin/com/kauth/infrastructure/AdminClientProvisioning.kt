package com.kauth.infrastructure

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.ThemeRepository
import org.slf4j.LoggerFactory

/**
 * Startup task: ensures the master tenant is properly configured for admin console OAuth.
 *
 * Handles three concerns that depend on KAUTH_BASE_URL (unavailable at migration time):
 *   1. Admin client redirect URI
 *   2. Master tenant issuer URL
 *   3. Master tenant logo URL (uses the built-in kotauth brand)
 */
class AdminClientProvisioning(
    private val tenantRepository: TenantRepository,
    private val applicationRepository: ApplicationRepository,
    private val themeRepository: ThemeRepository,
    private val baseUrl: String,
) {
    companion object {
        const val ADMIN_CLIENT_ID = "kotauth-admin"
    }

    private val log = LoggerFactory.getLogger(AdminClientProvisioning::class.java)

    fun provision() {
        val master = tenantRepository.findBySlug(Tenant.MASTER_SLUG)
        if (master == null) {
            log.warn("Master tenant not found — admin OAuth client not provisioned")
            return
        }
        provisionRedirectUri(master)
        provisionMasterTenantDefaults(master)
    }

    private fun provisionRedirectUri(master: Tenant) {
        val callbackUri = "$baseUrl/admin/callback"
        val adminClient = applicationRepository.findByClientId(master.id, ADMIN_CLIENT_ID)

        if (adminClient == null) {
            applicationRepository.create(
                tenantId = master.id,
                clientId = ADMIN_CLIENT_ID,
                name = "KotAuth Admin Console",
                description = "Built-in OAuth client for the admin console — Authorization Code + PKCE",
                accessType = "public",
                redirectUris = listOf(callbackUri),
            )
        } else if (adminClient.redirectUris != listOf(callbackUri)) {
            applicationRepository.update(
                appId = adminClient.id,
                name = adminClient.name,
                description = adminClient.description,
                accessType = adminClient.accessType.value,
                redirectUris = listOf(callbackUri),
            )
        }
    }

    /**
     * Ensures the master tenant has a proper issuer URL and logo.
     * Only sets these if they are currently blank/null — respects admin overrides.
     */
    private fun provisionMasterTenantDefaults(master: Tenant) {
        var needsUpdate = false
        var updated = master

        // Set issuer URL from base URL if not configured or still the V1 placeholder.
        // Respects admin overrides — only replaces blank or the known placeholder.
        val expectedIssuer = "$baseUrl/t/master"
        if (master.issuerUrl.isNullOrBlank() || master.issuerUrl == "https://kauth.example.com") {
            updated = updated.copy(issuerUrl = expectedIssuer)
            needsUpdate = true
        }

        if (needsUpdate) {
            tenantRepository.update(updated)
        }

        // Set logo URL if not already configured
        val logoUrl = "$baseUrl/static/brand/kotauth-negative.svg"
        if (master.theme.logoUrl.isNullOrBlank()) {
            themeRepository.upsert(master.id, master.theme.copy(logoUrl = logoUrl))
        }
    }
}
