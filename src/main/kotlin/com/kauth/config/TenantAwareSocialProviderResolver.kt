package com.kauth.config

import com.kauth.adapter.social.HttpFormPoster
import com.kauth.adapter.social.OidcProviderAdapter
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.OidcDiscoveryPort
import com.kauth.domain.port.OidcEndpointOverrides
import com.kauth.domain.port.SocialProviderPort
import com.kauth.domain.port.SocialProviderResolver
import com.kauth.domain.service.OidcTokenValidator

/**
 * Resolves a provider key to the adapter that speaks it: the compiled-in OAuth2 adapters for the
 * reserved keys, and an [OidcProviderAdapter] built from the tenant's own provider row otherwise.
 *
 * The adapter is built per resolve rather than cached. It holds no state worth keeping — the two
 * caches that matter, the discovery document and the JWKS, live inside the ports it is handed and
 * are shared across every adapter this resolver builds. Caching the adapter itself would buy one
 * object allocation and owe an invalidation rule, and an operator who fixes a client id would keep
 * failing to log in until the cache aged out.
 */
class TenantAwareSocialProviderResolver(
    private val compiledIn: Map<ProviderKey, SocialProviderPort>,
    private val identityProviders: IdentityProviderRepository,
    private val discovery: OidcDiscoveryPort,
    private val tokenValidator: OidcTokenValidator,
    private val formPoster: HttpFormPoster,
) : SocialProviderResolver {
    override fun resolve(
        tenantId: TenantId,
        key: ProviderKey,
    ): SocialProviderPort? {
        compiledIn[key]?.let { return it }

        val row = identityProviders.findByTenantAndProvider(tenantId, key) ?: return null
        // A row is only brokerable when it says how: an oauth2-kind row with no compiled-in
        // adapter, or an OIDC row with no issuer, names no endpoints anyone could reach.
        if (row.kind != ProviderKind.OIDC) return null
        val issuer = row.issuer ?: return null

        return OidcProviderAdapter(
            provider = key,
            issuer = issuer,
            scopes = row.scopes.split(" ").filter { it.isNotBlank() },
            discovery = discovery,
            validator = tokenValidator,
            http = formPoster,
            overrides =
                OidcEndpointOverrides(
                    authorizationEndpoint = row.authorizationEndpoint,
                    tokenEndpoint = row.tokenEndpoint,
                    jwksUri = row.jwksUri,
                ),
        )
    }
}
