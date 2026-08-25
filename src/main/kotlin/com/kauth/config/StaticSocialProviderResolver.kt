package com.kauth.config

import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialProviderResolver

/**
 * Phase 1 resolver: the two compiled-in adapters, keyed by their reserved keys. [tenantId] is
 * unused here and present because Phase 2 resolves an OIDC adapter from that tenant's provider
 * row — taking it now keeps Phase 2 from being a signature change.
 */
class StaticSocialProviderResolver(
    private val adapters: Map<ProviderKey, SocialProviderPort>,
) : SocialProviderResolver {
    override fun resolve(
        tenantId: TenantId,
        key: ProviderKey,
    ): SocialProviderPort? = adapters[key]
}
