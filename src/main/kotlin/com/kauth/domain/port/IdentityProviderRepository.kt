package com.kauth.domain.port

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialProvider

/**
 * Port — persistence contract for identity provider configurations.
 * Implemented by PostgresIdentityProviderRepository.
 */
interface IdentityProviderRepository {

    /** Returns all configured (and enabled) providers for the given tenant. */
    fun findEnabledByTenant(tenantId: Int): List<IdentityProvider>

    /** Returns all providers for the given tenant (including disabled). */
    fun findAllByTenant(tenantId: Int): List<IdentityProvider>

    /** Finds a specific provider config, or null if not configured. */
    fun findByTenantAndProvider(tenantId: Int, provider: SocialProvider): IdentityProvider?

    /** Persists a new provider configuration and returns it with the generated id. */
    fun save(provider: IdentityProvider): IdentityProvider

    /** Updates an existing provider configuration. */
    fun update(provider: IdentityProvider): IdentityProvider

    /** Deletes a provider configuration. */
    fun delete(tenantId: Int, provider: SocialProvider)
}
