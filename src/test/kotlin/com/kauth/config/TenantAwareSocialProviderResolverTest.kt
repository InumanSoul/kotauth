package com.kauth.config

import com.kauth.adapter.social.HttpFormPoster
import com.kauth.adapter.social.HttpJsonResponse
import com.kauth.adapter.social.OidcProviderAdapter
import com.kauth.adapter.token.JavaJwtVerifierAdapter
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.OidcRequestBinding
import com.kauth.domain.service.OidcTokenValidator
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakeOidcDiscoveryPort
import com.kauth.fakes.FakeOidcIssuer
import com.kauth.fakes.FakeSocialProviderPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [TenantAwareSocialProviderResolver] — which adapter a provider key resolves to, and
 * which rows are not brokerable at all.
 */
class TenantAwareSocialProviderResolverTest {
    private val acme = TenantId(1)
    private val other = TenantId(2)
    private val oriana = requireNotNull(ProviderKey.of("oriana"))

    private val issuer = FakeOidcIssuer()
    private val idpRepo = FakeIdentityProviderRepository()
    private val discovery = FakeOidcDiscoveryPort(issuer = issuer.issuer)
    private val google = FakeSocialProviderPort(ProviderKey.GOOGLE)

    private val resolver =
        TenantAwareSocialProviderResolver(
            compiledIn = mapOf(ProviderKey.GOOGLE to google),
            identityProviders = idpRepo,
            discovery = discovery,
            tokenValidator = OidcTokenValidator(issuer, JavaJwtVerifierAdapter()),
            formPoster = HttpFormPoster { _, _ -> HttpJsonResponse(200, "{}") },
        )

    private fun oidcRow(
        tenantId: TenantId = acme,
        issuerUrl: String? = issuer.issuer,
        authorizationEndpoint: String? = null,
        scopes: String = "openid email",
    ) = IdentityProvider(
        tenantId = tenantId,
        provider = oriana,
        clientId = "oriana-client",
        clientSecret = "oriana-secret",
        kind = ProviderKind.OIDC,
        issuer = issuerUrl,
        authorizationEndpoint = authorizationEndpoint,
        scopes = scopes,
    )

    private fun authorizationUrlFrom(port: com.kauth.domain.port.SocialProviderPort) =
        port.buildAuthorizationUrl(
            clientId = "oriana-client",
            redirectUri = "https://kotauth.example/cb",
            state = "state",
            scopes = emptyList(),
            binding = OidcRequestBinding(nonce = "n", codeVerifier = "v"),
        )

    @Test
    fun `a reserved key resolves to its compiled-in adapter, without touching the repository`() {
        idpRepo.add(oidcRow())

        assertSame(google, resolver.resolve(acme, ProviderKey.GOOGLE))
    }

    @Test
    fun `an unreserved key with an OIDC row resolves to an adapter for that key`() {
        idpRepo.add(oidcRow())

        val resolved = assertIs<OidcProviderAdapter>(resolver.resolve(acme, oriana))

        assertEquals(oriana, resolved.provider)
    }

    @Test
    fun `an unreserved key with no row resolves to nothing`() {
        assertNull(resolver.resolve(acme, oriana))
    }

    @Test
    fun `a row belonging to another tenant does not resolve`() {
        idpRepo.add(oidcRow(tenantId = other))

        assertNull(resolver.resolve(acme, oriana), "A provider row must never cross a tenant boundary")
    }

    @Test
    fun `an unreserved key whose row is not an OIDC row resolves to nothing`() {
        // No compiled-in adapter and no issuer to discover: nothing could serve this row.
        idpRepo.add(oidcRow().copy(kind = ProviderKind.OAUTH2))

        assertNull(resolver.resolve(acme, oriana))
    }

    @Test
    fun `an OIDC row with no issuer resolves to nothing`() {
        idpRepo.add(oidcRow(issuerUrl = null))

        assertNull(resolver.resolve(acme, oriana))
    }

    @Test
    fun `the adapter is built from the row - its issuer, its scopes and its pinned endpoints`() {
        idpRepo.add(oidcRow(authorizationEndpoint = "https://pinned.example/authorize", scopes = "openid email"))

        val url = authorizationUrlFrom(resolver.resolve(acme, oriana)!!)

        assertEquals(listOf(issuer.issuer), discovery.discovered, "The row's issuer is the one resolved")
        assertTrue(url.startsWith("https://pinned.example/authorize?"), "The row's pinned endpoint must win")
        assertTrue(url.contains("scope=openid+email"), "The row's scopes must be requested, got: $url")
    }
}
