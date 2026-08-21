package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Verifies the token endpoint refuses grants a client is not registered for
 * (RFC 6749 §5.2 `unauthorized_client`), and that clients registered for a
 * grant — including V59-backfilled PUBLIC clients — can still complete it.
 */
class ApplicationGrantEnforcementTest {
    // -------------------------------------------------------------------------
    // Fakes — same wiring as OAuthServiceTest
    // -------------------------------------------------------------------------

    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val tokens = FakeTokenPort()
    private val auditLog = FakeAuditLogPort()
    private val resourceServers = FakeResourceServerRepository()

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
            resourceServerRepository = resourceServers,
        )

    private val testTenant = Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null)
    private val testUser =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = "hashed:pw",
            enabled = true,
        )

    private val pkceVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk" // 43-char base64url
    private val pkceChallenge = sha256Base64Url(pkceVerifier)

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        apps.clear()
        authCodes.clear()
        sessions.clear()
        auditLog.clear()
        tokens.reset()
        resourceServers.clear()

        tenants.add(testTenant)
        users.add(testUser)
    }

    // -------------------------------------------------------------------------
    // Fixture builders
    // -------------------------------------------------------------------------

    private fun confidentialApp(
        clientId: String,
        grants: Set<GrantType>,
    ): Application =
        Application(
            id = ApplicationId(0),
            tenantId = TenantId(1),
            clientId = clientId,
            name = clientId,
            description = null,
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = listOf("https://$clientId.example.com/callback"),
            grantTypes = grants,
        )

    private fun publicApp(
        clientId: String,
        grants: Set<GrantType>,
    ): Application =
        Application(
            id = ApplicationId(0),
            tenantId = TenantId(1),
            clientId = clientId,
            name = clientId,
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://$clientId.example.com/callback"),
            grantTypes = grants,
        )

    private fun bearerOnlyApp(
        clientId: String,
        grants: Set<GrantType>,
    ): Application =
        Application(
            id = ApplicationId(0),
            tenantId = TenantId(1),
            clientId = clientId,
            name = clientId,
            description = null,
            accessType = AccessType.BEARER_ONLY,
            enabled = true,
            grantTypes = grants,
        )

    // =========================================================================
    // client_credentials
    // =========================================================================

    @Test
    fun `client credentials is refused when the client is not registered for it`() {
        apps.add(
            confidentialApp(clientId = "web-only", grants = setOf(GrantType.AUTHORIZATION_CODE)),
            secretHash = hasher.hash("secret"),
        )

        val result = svc.clientCredentials("acme", "web-only", "secret", "read")

        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.UnauthorizedClient>(result.error)
    }

    @Test
    fun `client credentials succeeds when the client is registered for it`() {
        apps.add(
            confidentialApp(clientId = "m2m", grants = setOf(GrantType.CLIENT_CREDENTIALS)),
            secretHash = hasher.hash("secret"),
        )

        val result = svc.clientCredentials("acme", "m2m", "secret", "read")

        assertIs<OAuthResult.Success<TokenResponse>>(result)
    }

    @Test
    fun `a bearer only client backfilled with no grants is refused client_credentials and authorization_code`() {
        // refresh_token is not covered here: a bearer-only client can never establish a session in the
        // first place (both flows below are refused), so there is no reachable state to exercise its guard.
        val app =
            apps.add(bearerOnlyApp(clientId = "api-only", grants = emptySet()), secretHash = hasher.hash("secret"))

        val credentialsResult = svc.clientCredentials("acme", "api-only", "secret", "read")
        assertIs<OAuthResult.Failure>(credentialsResult)
        // BEARER_ONLY is refused earlier by the access-type check, not the grant check —
        // either way it must never mint a client_credentials token.
        assertIs<OAuthError.InvalidClient>(credentialsResult.error)

        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = UserId(10),
                clientId = app.clientId,
                redirectUri = "https://api-only.example.com/callback",
                scopes = "openid",
                codeChallenge = null,
                codeChallengeMethod = null,
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Failure>(issueResult)
        assertIs<OAuthError.UnauthorizedClient>(issueResult.error)
    }

    // =========================================================================
    // authorization_code
    // =========================================================================

    @Test
    fun `authorization code issuance is refused when the client is not registered for it`() {
        val app = apps.add(publicApp(clientId = "no-auth-code", grants = setOf(GrantType.REFRESH_TOKEN)))

        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = UserId(10),
                clientId = app.clientId,
                redirectUri = app.redirectUris.first(),
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )

        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.UnauthorizedClient>(result.error)
    }

    @Test
    fun `authorization code issuance succeeds when the client is registered for it`() {
        val app =
            apps.add(publicApp(clientId = "spa", grants = setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN)))

        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = UserId(10),
                clientId = app.clientId,
                redirectUri = app.redirectUris.first(),
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )

        assertIs<OAuthResult.Success<AuthorizationCode>>(result)
    }

    // =========================================================================
    // exchangeAuthorizationCode — guards every code producer, not just
    // issueAuthorizationCode (e.g. EmailOtpService writes codes directly to
    // the repository, bypassing issueAuthorizationCode's grant check).
    // =========================================================================

    @Test
    fun `exchange refuses a directly-saved code when the client lacks authorization_code`() {
        val app = apps.add(publicApp(clientId = "no-auth-code-exchange", grants = setOf(GrantType.REFRESH_TOKEN)))
        val code = saveCodeDirectly(app)

        val result =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = code,
                clientId = app.clientId,
                redirectUri = app.redirectUris.first(),
                codeVerifier = null,
                clientSecret = null,
            )

        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.UnauthorizedClient>(result.error)
    }

    @Test
    fun `exchange is refused when the code was issued to a different client`() {
        val issuedTo = apps.add(publicApp(clientId = "issued-to", grants = setOf(GrantType.AUTHORIZATION_CODE)))
        val redeemedBy =
            apps.add(publicApp(clientId = "redeemed-by", grants = setOf(GrantType.AUTHORIZATION_CODE)))
        val code = saveCodeDirectly(issuedTo)

        val result =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = code,
                clientId = redeemedBy.clientId,
                redirectUri = redeemedBy.redirectUris.first(),
                codeVerifier = null,
                clientSecret = null,
            )

        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidGrant>(result.error)
    }

    // =========================================================================
    // refresh_token
    // =========================================================================

    @Test
    fun `refresh is refused when the client is not registered for it`() {
        // Exchange while the client still holds refresh_token so a refresh credential exists,
        // then narrow the client's grants — mirrors an admin removing refresh_token after issuance.
        val grantedApp =
            apps.add(
                publicApp(
                    clientId = "no-refresh",
                    grants = setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
                ),
            )
        val refreshToken = establishSession(grantedApp)
        val app = apps.add(grantedApp.copy(grantTypes = setOf(GrantType.AUTHORIZATION_CODE)))

        val result = svc.refreshTokens(tenantSlug = "acme", refreshToken = refreshToken, clientId = app.clientId)

        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.UnauthorizedClient>(result.error)
    }

    @Test
    fun `refresh succeeds when the client is registered for it`() {
        val grants = setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN)
        val app = apps.add(publicApp(clientId = "spa-refresh", grants = grants))
        val refreshToken = establishSession(app)

        val result = svc.refreshTokens(tenantSlug = "acme", refreshToken = refreshToken, clientId = app.clientId)

        assertIs<OAuthResult.Success<TokenResponse>>(result)
    }

    // =========================================================================
    // V59 backfill parity
    // =========================================================================

    @Test
    fun `a V59-backfilled public client can still complete authorization_code and refresh`() {
        val app =
            apps.add(
                publicApp(
                    clientId = "legacy-public",
                    grants = GrantType.defaultsFor(AccessType.PUBLIC),
                ),
            )
        val refreshToken = establishSession(app)

        val result = svc.refreshTokens(tenantSlug = "acme", refreshToken = refreshToken, clientId = app.clientId)

        assertIs<OAuthResult.Success<TokenResponse>>(result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun establishSession(client: Application): String {
        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = UserId(10),
                clientId = client.clientId,
                redirectUri = client.redirectUris.first(),
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        val code = (issueResult as OAuthResult.Success<AuthorizationCode>).value.code
        val exchangeResult =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = code,
                clientId = client.clientId,
                redirectUri = client.redirectUris.first(),
                codeVerifier = pkceVerifier,
                clientSecret = null,
            )
        return (exchangeResult as OAuthResult.Success<TokenResponse>).value.refresh_token!!
    }

    /**
     * Mirrors EmailOtpService.issueAuthorizationCodeFor: writes a code straight to the
     * repository for the given client, bypassing OAuthService.issueAuthorizationCode
     * (and its grant check) entirely — exactly what the back-channel email-OTP flow does.
     */
    private fun saveCodeDirectly(client: Application): String {
        val code = "direct-code-${client.clientId}"
        authCodes.save(
            AuthorizationCode(
                code = code,
                tenantId = TenantId(1),
                clientId = client.id,
                userId = UserId(10),
                redirectUri = client.redirectUris.first(),
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(300),
            ),
        )
        return code
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
