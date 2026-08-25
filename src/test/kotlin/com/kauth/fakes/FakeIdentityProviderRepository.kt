package com.kauth.fakes

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.IdentityProviderRepository

/**
 * In-memory IdentityProviderRepository for unit tests.
 */
class FakeIdentityProviderRepository : IdentityProviderRepository {
    private val store = mutableMapOf<Int, IdentityProvider>()
    private var nextId = 1

    fun seed(
        tenantId: TenantId,
        provider: String,
        enabled: Boolean = true,
    ) {
        val providerKey = requireNotNull(ProviderKey.of(provider)) { "Not a provider key: $provider" }
        add(
            IdentityProvider(
                tenantId = tenantId,
                provider = providerKey,
                clientId = "client-$provider",
                clientSecret = "secret-$provider",
                enabled = enabled,
            ),
        )
    }

    fun add(provider: IdentityProvider): IdentityProvider {
        val p = if (provider.id == null) provider.copy(id = nextId++) else provider
        store[p.id!!] = p
        return p
    }

    fun clear() {
        store.clear()
        nextId = 1
    }

    override fun findEnabledByTenant(tenantId: TenantId): List<IdentityProvider> =
        store.values.filter { it.tenantId == tenantId && it.enabled }

    override fun findAllByTenant(tenantId: TenantId): List<IdentityProvider> =
        store.values.filter { it.tenantId == tenantId }

    override fun findByTenantAndProvider(
        tenantId: TenantId,
        provider: ProviderKey,
    ): IdentityProvider? = store.values.find { it.tenantId == tenantId && it.provider == provider }

    override fun save(provider: IdentityProvider): IdentityProvider {
        val p = if (provider.id == null) provider.copy(id = nextId++) else provider
        store[p.id!!] = p
        return p
    }

    override fun update(provider: IdentityProvider): IdentityProvider {
        store[provider.id!!] = provider
        return provider
    }

    override fun delete(
        tenantId: TenantId,
        provider: ProviderKey,
    ) {
        store.entries.removeIf { it.value.tenantId == tenantId && it.value.provider == provider }
    }
}
