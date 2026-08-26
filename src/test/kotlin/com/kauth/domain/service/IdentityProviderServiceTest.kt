package com.kauth.domain.service

import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeIdentityProviderRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IdentityProviderService] — the single validation point the admin UI
 * and the REST API both call.
 */
class IdentityProviderServiceTest {
    private val repo = FakeIdentityProviderRepository()
    private val service = IdentityProviderService(repo)

    private val tenantId = TenantId(1)
    private val otherTenantId = TenantId(2)
    private val acme = ProviderKey.of("acme-idp")!!

    @BeforeTest
    fun setUp() {
        repo.clear()
    }

    @Test
    fun `an oidc provider requires an issuer`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = null,
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "requires an issuer URL")
    }

    @Test
    fun `an over-long client secret is refused like every other bounded field`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s".repeat(513),
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "client secret must be 512 characters or fewer")
    }

    @Test
    fun `a client secret at the bound is accepted`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s".repeat(512),
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
            )
        assertIs<AdminResult.Success<IdentityProvider>>(result)
    }

    @Test
    fun `the model never prints its client secret`() {
        val row =
            IdentityProvider(
                id = 1,
                tenantId = tenantId,
                provider = acme,
                clientId = "c",
                clientSecret = "super-secret-value",
                kind = ProviderKind.OIDC,
            )

        // Nothing logs a whole row today; a data class toString() is one interpolation away
        // from a secret in a log file, and that is not a property to leave to convention.
        assertFalse(row.toString().contains("super-secret-value"), "toString() leaked the secret: $row")
    }

    @Test
    fun `allowed domains are trimmed lower-cased and de-duplicated on write`() {
        val saved =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
                jitEnabled = true,
                jitAllowedDomains = listOf(" Oriana.com.py ", "oriana.com.py", ""),
            )
        assertEquals(listOf("oriana.com.py"), (saved as AdminResult.Success<IdentityProvider>).value.jitAllowedDomains)
    }

    @Test
    fun `a provider key cannot be changed once saved`() {
        val created =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
            )
        val id = assertIs<AdminResult.Success<IdentityProvider>>(created).value.id
        assertNotNull(id)

        val renamed =
            service.save(
                tenantId,
                key = ProviderKey.of("acme-idp-2")!!,
                clientId = "c",
                clientSecret = "s",
                id = id,
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
            )
        val renameError = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(renamed).error)
        assertContains(renameError.message, "fixed once saved")
        // the stored row keeps its original key
        assertNotNull(service.get(tenantId, acme))
        assertNull(service.get(tenantId, ProviderKey.of("acme-idp-2")!!))
    }

    @Test
    fun `a new oidc provider is stored with the documented defaults`() {
        val saved =
            service.save(
                tenantId,
                key = acme,
                clientId = " client ",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = " https://issuer.example ",
                displayName = "  ",
            )
        val provider = assertIs<AdminResult.Success<IdentityProvider>>(saved).value
        assertEquals("client", provider.clientId)
        assertEquals("https://issuer.example", provider.issuer)
        assertNull(provider.displayName)
        assertEquals("openid email profile", provider.scopes)
        assertEquals(false, provider.jitEnabled)
        assertEquals(emptyList<String>(), provider.jitAllowedDomains)
        assertEquals(true, provider.enabled)
    }

    @Test
    fun `an oidc issuer must be an absolute https url`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "issuer.example",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "The issuer must be an https URL")
    }

    @Test
    fun `oidc scopes must request openid`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
                scopes = "email profile",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "must request the 'openid' scope")
    }

    @Test
    fun `a reserved key stays bound to its compiled-in oauth2 adapter`() {
        val asOidc =
            service.save(
                tenantId,
                key = ProviderKey.GOOGLE,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "https://accounts.google.com",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(asOidc).error)
        assertContains(error.message, "must stay an OAuth2 provider")

        val asOauth2 = service.save(tenantId, key = ProviderKey.GOOGLE, clientId = "c", clientSecret = "s")
        assertIs<AdminResult.Success<*>>(asOauth2)
    }

    @Test
    fun `a key with no compiled-in adapter cannot be saved as oauth2`() {
        val result = service.save(tenantId, key = acme, clientId = "c", clientSecret = "s")
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "has no built-in adapter")
    }

    @Test
    fun `a client secret is required when creating but optional when updating`() {
        val missing = service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = null)
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(missing).error)
        assertContains(error.message, "A client secret is required")

        val created = service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s")
        assertIs<AdminResult.Success<*>>(created)

        val updated = service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c2", clientSecret = null)
        val provider = assertIs<AdminResult.Success<IdentityProvider>>(updated).value
        assertEquals("c2", provider.clientId)
        assertEquals("s", provider.clientSecret)
    }

    @Test
    fun `saving the same key twice updates in place rather than creating a second row`() {
        service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s")
        service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c2", clientSecret = "s2")
        assertEquals(1, service.list(tenantId).size)
    }

    @Test
    fun `saving against an id from another tenant is rejected`() {
        val created = service.save(otherTenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s")
        val id = assertIs<AdminResult.Success<IdentityProvider>>(created).value.id

        val crossTenant =
            service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s", id = id)
        // NotFound, not Validation: the row is invisible to this tenant rather than invalid
        val error = assertIs<AdminError.NotFound>(assertIs<AdminResult.Failure>(crossTenant).error)
        assertContains(error.message, "No identity provider with id $id")
    }

    @Test
    fun `list and get are scoped to the tenant`() {
        service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s")
        service.save(otherTenantId, key = ProviderKey.GOOGLE, clientId = "c", clientSecret = "s")

        assertEquals(listOf(ProviderKey.GITHUB), service.list(tenantId).map { it.provider })
        assertNull(service.get(tenantId, ProviderKey.GOOGLE))
    }

    @Test
    fun `deleting an unconfigured provider reports not found`() {
        val result = service.delete(tenantId, acme)
        val error = assertIs<AdminError.NotFound>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "No identity provider 'acme-idp'")
    }

    @Test
    fun `deleting a configured provider removes it`() {
        service.save(tenantId, key = ProviderKey.GITHUB, clientId = "c", clientSecret = "s")
        assertIs<AdminResult.Success<*>>(service.delete(tenantId, ProviderKey.GITHUB))
        assertTrue(service.list(tenantId).isEmpty())
    }

    @Test
    fun `an http issuer is rejected`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "http://issuer.example",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        // OIDC Core section 2. Over plaintext the discovery document and the issuer inside it are
        // rewritten together, so the adapter's issuer check compares the attacker's value with itself.
        assertContains(error.message, "The issuer must be an https URL")
    }

    @Test
    fun `an http issuer is accepted on loopback for local development`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "http://localhost:8080/realms/kotauth",
                jwksUri = "http://127.0.0.1:8080/realms/kotauth/protocol/openid-connect/certs",
            )
        val saved = assertIs<AdminResult.Success<IdentityProvider>>(result).value
        assertEquals("http://localhost:8080/realms/kotauth", saved.issuer)
    }

    @Test
    fun `a host that merely looks like loopback is rejected`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "http://localhost.attacker.example",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "The issuer must be an https URL")
    }

    @Test
    fun `an http endpoint override is rejected even under an https issuer`() {
        val result =
            service.save(
                tenantId,
                key = acme,
                clientId = "c",
                clientSecret = "s",
                kind = ProviderKind.OIDC,
                issuer = "https://issuer.example",
                jwksUri = "http://issuer.example/jwks",
            )
        val error = assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertContains(error.message, "The JWKS URI must be an https URL")
    }
}
