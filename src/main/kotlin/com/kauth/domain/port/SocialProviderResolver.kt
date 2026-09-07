package com.kauth.domain.port

import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantId

/**
 * Port — resolves the adapter that speaks to a given provider for a given tenant.
 *
 * The domain service must not hold a composition-time map: an OIDC provider is created at
 * runtime from a tenant's configuration row, so its adapter cannot exist when the graph is built.
 */
interface SocialProviderResolver {
    fun resolve(
        tenantId: TenantId,
        key: ProviderKey,
    ): SocialProviderPort?
}
