package com.kauth.domain.port

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantId

/**
 * Port — persistence contract for identity provider configurations.
 * Implemented by PostgresIdentityProviderRepository.
 */
interface IdentityProviderRepository {
    /** Returns all configured (and enabled) providers for the given tenant. */
    fun findEnabledByTenant(tenantId: TenantId): List<IdentityProvider>

    /** Returns all providers for the given tenant (including disabled). */
    fun findAllByTenant(tenantId: TenantId): List<IdentityProvider>

    /** Finds a provider config by its surrogate id within the tenant, or null. */
    fun findById(
        tenantId: TenantId,
        id: Int,
    ): IdentityProvider?

    /** Finds a specific provider config, or null if not configured. */
    fun findByTenantAndProvider(
        tenantId: TenantId,
        provider: ProviderKey,
    ): IdentityProvider?

    /** Persists a new provider configuration and returns it with the generated id. */
    fun save(provider: IdentityProvider): IdentityProvider

    /** Updates an existing provider configuration. */
    fun update(provider: IdentityProvider): IdentityProvider

    /** Deletes a provider configuration. */
    fun delete(
        tenantId: TenantId,
        provider: ProviderKey,
    )
}
