package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for claim mapping on token issuance paths.
 *
 * Covers the acceptance criteria from the user-attributes feature spec:
 *  - attribute + mapper present → claim injected into access and id tokens
 *  - mapper includeInId=false → claim absent from id token
 *  - DELETE attribute → claim absent from subsequent tokens
 *  - DELETE mapper → claim absent even when attribute still set
 *  - zero mappers → token payload identical to pre-feature
 *  - refresh_token flow picks up attribute changes within next issuance
 */
class TokenClaimMappingTest {
    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val tokens = FakeTokenPort()
    private val auditLog = FakeAuditLogPort()
    private val userAttributes = FakeUserAttributeRepository()
    private val claimMappers = FakeTenantClaimMapperRepository()

    private val userAttributeService =
        UserAttributeService(
            userAttributeRepository = userAttributes,
            userRepository = users,
        )

    private val claimMapperService =
        ClaimMapperService(
            mapperRepository = claimMappers,
        )

    private val svc =
        OAuthService(
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            sessionRepository = sessions,
            authCodeRepository = authCodes,
            tokenPort = tokens,
            passwordHasher = hasher,
            auditLog = auditLog,
            userAttributeRepository = userAttributes,
            claimMappersFor = claimMapperService::list,
        )

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val tenantId = TenantId(1)
    private val userId = UserId(10)

    private val testTenant = Tenant(id = tenantId, slug = "acme", displayName = "Acme", issuerUrl = null)

    private val testUser =
        User(
            id = userId,
            tenantId = tenantId,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = "hashed:pw",
        )

    private val publicClient =
        Application(
            id = ApplicationId(1),
            tenantId = tenantId,
            clientId = "spa-app",
            name = "SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://app.example.com/callback"),
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
        )

    private val pkceVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val pkceChallenge =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pkceVerifier.toByteArray()),
            )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        apps.clear()
        authCodes.clear()
        sessions.clear()
        auditLog.clear()
        tokens.reset()
        userAttributes.clear()
        claimMappers.clear()

        tenants.add(testTenant)
        users.add(testUser)
        apps.add(publicClient)
    }

    // =========================================================================
    // Happy path — authorization_code flow
    // =========================================================================

    @Test
    fun `access token includes custom claim when attribute and mapper are both configured`() {
        userAttributes.upsert(
            UserAttribute(userId, tenantId, "plan", "trial", Instant.now()),
        )
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))

        exchangeAuthCode()

        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])
    }

    @Test
    fun `id token includes custom claim when mapper has includeInId=true`() {
        userAttributes.upsert(
            UserAttribute(userId, tenantId, "plan", "trial", Instant.now()),
        )
        claimMapperService.upsert(
            TenantClaimMapper(tenantId, "plan", "custom:plan", includeInAccess = true, includeInId = true),
        )

        exchangeAuthCode()

        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])
        assertEquals("trial", tokens.lastCustomIdClaims["custom:plan"])
    }

    @Test
    fun `id token does NOT include custom claim when includeInId=false`() {
        userAttributes.upsert(
            UserAttribute(userId, tenantId, "plan", "trial", Instant.now()),
        )
        claimMapperService.upsert(
            TenantClaimMapper(tenantId, "plan", "custom:plan", includeInAccess = true, includeInId = false),
        )

        exchangeAuthCode()

        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])
        assertTrue("custom:plan" !in tokens.lastCustomIdClaims)
    }

    @Test
    fun `multiple mappers project multiple claims in a single issuance`() {
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))
        userAttributes.upsert(UserAttribute(userId, tenantId, "trial_ends", "2026-05-21", Instant.now()))
        userAttributes.upsert(UserAttribute(userId, tenantId, "sifen_env", "staging", Instant.now()))

        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "trial_ends", "custom:trial_ends"))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "sifen_env", "custom:sifen_env"))

        exchangeAuthCode()

        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])
        assertEquals("2026-05-21", tokens.lastCustomAccessClaims["custom:trial_ends"])
        assertEquals("staging", tokens.lastCustomAccessClaims["custom:sifen_env"])
    }

    // =========================================================================
    // Missing attribute / missing mapper → claim absent
    // =========================================================================

    @Test
    fun `attribute deleted via service no longer appears in subsequent tokens`() {
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))

        exchangeAuthCode()
        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])

        userAttributeService.delete(userId, tenantId, "plan")
        exchangeAuthCode()

        assertTrue("custom:plan" !in tokens.lastCustomAccessClaims)
    }

    @Test
    fun `mapper deleted removes the claim even if attribute still set`() {
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))

        exchangeAuthCode()
        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])

        claimMapperService.delete(tenantId, "plan")
        exchangeAuthCode()

        assertTrue("custom:plan" !in tokens.lastCustomAccessClaims)
        // Attribute itself is untouched.
        assertEquals("trial", userAttributes.findAll(userId, tenantId)["plan"])
    }

    @Test
    fun `mapper without matching attribute silently omits the claim`() {
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))
        // No attribute set.

        exchangeAuthCode()

        assertTrue("custom:plan" !in tokens.lastCustomAccessClaims)
    }

    // =========================================================================
    // Zero regression — no mappers configured
    // =========================================================================

    @Test
    fun `zero mappers configured yields empty custom claim maps`() {
        // No mappers, no attributes — baseline.
        exchangeAuthCode()

        assertTrue(tokens.lastCustomAccessClaims.isEmpty())
        assertTrue(tokens.lastCustomIdClaims.isEmpty())
    }

    @Test
    fun `OAuthService without user-attribute wiring yields empty claims`() {
        val svcWithoutAttributes =
            OAuthService(
                tenantRepository = tenants,
                userRepository = users,
                applicationRepository = apps,
                sessionRepository = sessions,
                authCodeRepository = authCodes,
                tokenPort = tokens,
                passwordHasher = hasher,
                auditLog = auditLog,
                // No userAttributeRepository, no claimMappersFor.
            )

        val code =
            (
                svcWithoutAttributes.issueAuthorizationCode(
                    tenantSlug = "acme",
                    userId = userId,
                    clientId = "spa-app",
                    redirectUri = "https://app.example.com/callback",
                    scopes = "openid",
                    codeChallenge = pkceChallenge,
                    codeChallengeMethod = "S256",
                    nonce = null,
                    state = null,
                ) as OAuthResult.Success<AuthorizationCode>
            ).value.code

        svcWithoutAttributes.exchangeAuthorizationCode(
            tenantSlug = "acme",
            code = code,
            clientId = "spa-app",
            redirectUri = "https://app.example.com/callback",
            codeVerifier = pkceVerifier,
            clientSecret = null,
        )

        assertTrue(tokens.lastCustomAccessClaims.isEmpty())
        assertTrue(tokens.lastCustomIdClaims.isEmpty())
    }

    // =========================================================================
    // Refresh token flow — eventually consistent
    // =========================================================================

    @Test
    fun `refresh token flow re-projects claims from current attribute state`() {
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))

        // First issuance — initial access + refresh token.
        val initial = exchangeAuthCode()
        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])

        // Billing flips the plan while the refresh token is still valid.
        userAttributeService.upsert(userId, tenantId, "plan", "pro")

        // Refresh request — must pick up the new value.
        val result =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = initial.refresh_token!!,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Success<*>>(result)

        assertEquals("pro", tokens.lastCustomAccessClaims["custom:plan"])
    }

    @Test
    fun `refresh token flow drops claim when mapper is deleted between issuances`() {
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))
        claimMapperService.upsert(TenantClaimMapper(tenantId, "plan", "custom:plan"))

        val initial = exchangeAuthCode()
        assertEquals("trial", tokens.lastCustomAccessClaims["custom:plan"])

        claimMapperService.delete(tenantId, "plan")

        val result =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = initial.refresh_token!!,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Success<*>>(result)

        assertTrue("custom:plan" !in tokens.lastCustomAccessClaims)
    }

    // =========================================================================
    // Tenant isolation — attributes from other tenants never bleed
    // =========================================================================

    @Test
    fun `mappers from a different tenant are ignored during issuance`() {
        val otherTenantId = TenantId(99)
        userAttributes.upsert(UserAttribute(userId, tenantId, "plan", "trial", Instant.now()))

        // Mapper registered in the WRONG tenant.
        claimMapperService.upsert(TenantClaimMapper(otherTenantId, "plan", "custom:plan"))

        exchangeAuthCode()

        assertTrue("custom:plan" !in tokens.lastCustomAccessClaims)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Runs a full authorization_code exchange with the openid scope and returns the token response. */
    private fun exchangeAuthCode() =
        run {
            val code =
                (
                    svc.issueAuthorizationCode(
                        tenantSlug = "acme",
                        userId = userId,
                        clientId = "spa-app",
                        redirectUri = "https://app.example.com/callback",
                        scopes = "openid",
                        codeChallenge = pkceChallenge,
                        codeChallengeMethod = "S256",
                        nonce = null,
                        state = null,
                    ) as OAuthResult.Success<AuthorizationCode>
                ).value.code

            val result =
                svc.exchangeAuthorizationCode(
                    tenantSlug = "acme",
                    code = code,
                    clientId = "spa-app",
                    redirectUri = "https://app.example.com/callback",
                    codeVerifier = pkceVerifier,
                    clientSecret = null,
                )
            assertIs<OAuthResult.Success<*>>(result)
            @Suppress("UNCHECKED_CAST")
            (result as OAuthResult.Success<com.kauth.domain.model.TokenResponse>).value
        }
}
