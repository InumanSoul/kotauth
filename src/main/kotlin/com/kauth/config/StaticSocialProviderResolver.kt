package com.kauth.config

import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialProviderResolver

/**
 * The compiled-in adapters, keyed by their reserved keys, and nothing else. [tenantId] is unused:
 * this resolver cannot reach a tenant's provider row, which is why the running graph now wires
 * [TenantAwareSocialProviderResolver] instead and passes this same map to it as its reserved half.
 *
 * Kept as the composition for tests that want the compiled-in adapters and no OIDC machinery.
 */
class StaticSocialProviderResolver(
    private val adapters: Map<ProviderKey, SocialProviderPort>,
) : SocialProviderResolver {
    override fun resolve(
        tenantId: TenantId,
        key: ProviderKey,
    ): SocialProviderPort? = adapters[key]
}
