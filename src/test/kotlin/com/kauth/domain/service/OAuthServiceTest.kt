package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [OAuthService].
 *
 * Covers:
 *   - Authorization Code issuance (including PKCE enforcement for public clients)
 *   - Code exchange: happy path, expired code, tampered PKCE verifier, replayed code
 *   - Client Credentials flow
 *   - Refresh Token rotation
 */
class OAuthServiceTest {
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
        )

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val testTenant = Tenant(id = 1, slug = "acme", displayName = "Acme", issuerUrl = null)
    private val testUser =
        User(
            id = 10,
            tenantId = 1,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = "hashed:pw",
            enabled = true,
        )

    /** Public client — PKCE required. */
    private val publicClient =
        Application(
            id = 1,
            tenantId = 1,
            clientId = "spa-app",
            name = "SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://app.example.com/callback"),
        )

    /** Confidential client — secret required, PKCE optional. */
    private val confidentialClient =
        Application(
            id = 2,
            tenantId = 1,
            clientId = "backend-app",
            name = "Backend",
            description = null,
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = listOf("https://backend.example.com/callback"),
        )

    // Stable PKCE pair used across multiple tests
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

        tenants.add(testTenant)
        users.add(testUser)
        apps.add(publicClient)
        apps.add(confidentialClient, secretHash = hasher.hash("secret123"))
    }

    // =========================================================================
    // issueAuthorizationCode
    // =========================================================================

    @Test
    fun `issueAuthorizationCode returns TenantNotFound for unknown slug`() {
        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "no-such",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.TenantNotFound>(result.error)
    }

    @Test
    fun `issueAuthorizationCode returns InvalidClient for unknown client_id`() {
        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "ghost-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidClient>(result.error)
    }

    @Test
    fun `issueAuthorizationCode returns InvalidRedirectUri for unregistered redirect URI`() {
        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://evil.attacker.com/steal",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidRedirectUri>(result.error)
    }

    @Test
    fun `issueAuthorizationCode returns PkceRequired for PUBLIC client without code_challenge`() {
        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = null, // ← missing
                codeChallengeMethod = null,
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.PkceRequired>(result.error)
    }

    @Test
    fun `issueAuthorizationCode succeeds for public client with valid PKCE`() {
        val result =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid profile",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = "nonce-xyz",
                state = "state-abc",
            )
        assertIs<OAuthResult.Success<AuthorizationCode>>(result)
        val code = result.value
        assertNotNull(code.code)
        assertEquals(pkceChallenge, code.codeChallenge)
        assertTrue(code.isValid)
        assertTrue(auditLog.hasEvent(AuditEventType.AUTHORIZATION_CODE_ISSUED))
    }

    // =========================================================================
    // exchangeAuthorizationCode
    // =========================================================================

    @Test
    fun `exchangeAuthorizationCode returns InvalidGrant for unknown code`() {
        val result =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = "no-such-code",
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                codeVerifier = pkceVerifier,
                clientSecret = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidGrant>(result.error)
    }

    @Test
    fun `exchangeAuthorizationCode returns InvalidGrant for expired code`() {
        // Save an already-expired code directly into the fake repository
        val expiredCode =
            AuthorizationCode(
                code = "expired-code",
                tenantId = 1,
                clientId = publicClient.id,
                userId = 10,
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                expiresAt = Instant.now().minusSeconds(120), // ← in the past
            )
        authCodes.save(expiredCode)

        val result =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = "expired-code",
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                codeVerifier = pkceVerifier,
                clientSecret = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidGrant>(result.error)
    }

    @Test
    fun `exchangeAuthorizationCode returns InvalidGrant when PKCE verifier does not match challenge`() {
        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        assertIs<OAuthResult.Success<*>>(issueResult)
        val code = (issueResult as OAuthResult.Success<AuthorizationCode>).value.code

        // Use a different verifier — SHA256 won't match the stored challenge
        val result =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = code,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                codeVerifier = "tampered-verifier-that-is-long-enough-for-pkce-spec",
                clientSecret = null,
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidGrant>(result.error)
    }

    @Test
    fun `exchangeAuthorizationCode succeeds and marks code as consumed`() {
        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        val code = (issueResult as OAuthResult.Success<AuthorizationCode>).value.code

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
        // Code must be consumed — replaying it must fail
        val replayResult =
            svc.exchangeAuthorizationCode(
                tenantSlug = "acme",
                code = code,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                codeVerifier = pkceVerifier,
                clientSecret = null,
            )
        assertIs<OAuthResult.Failure>(replayResult)
        assertIs<OAuthError.InvalidGrant>(replayResult.error)
    }

    @Test
    fun `exchangeAuthorizationCode revokes all user sessions on replay attack`() {
        // First: create and exchange a valid code to get a session
        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                scopes = "openid",
                codeChallenge = pkceChallenge,
                codeChallengeMethod = "S256",
                nonce = null,
                state = null,
            )
        val code = (issueResult as OAuthResult.Success<AuthorizationCode>).value.code
        svc.exchangeAuthorizationCode(
            tenantSlug = "acme",
            code = code,
            clientId = "spa-app",
            redirectUri = "https://app.example.com/callback",
            codeVerifier = pkceVerifier,
            clientSecret = null,
        )

        // Now replay the already-used code — sessions must be revoked
        svc.exchangeAuthorizationCode(
            tenantSlug = "acme",
            code = code,
            clientId = "spa-app",
            redirectUri = "https://app.example.com/callback",
            codeVerifier = pkceVerifier,
            clientSecret = null,
        )

        val activeSessions = sessions.findActiveByUser(1, 10)
        assertEquals(0, activeSessions.size, "All sessions must be revoked after a replay attack")
        assertTrue(auditLog.hasEvent(AuditEventType.SESSION_REVOKED))
    }

    // =========================================================================
    // clientCredentials
    // =========================================================================

    @Test
    fun `clientCredentials returns InvalidClient for wrong secret`() {
        val result =
            svc.clientCredentials(
                tenantSlug = "acme",
                clientId = "backend-app",
                clientSecret = "wrong-secret",
                scopes = "openid",
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidClient>(result.error)
    }

    @Test
    fun `clientCredentials returns InvalidClient for public client`() {
        // Public clients are not allowed to use client_credentials flow
        val result =
            svc.clientCredentials(
                tenantSlug = "acme",
                clientId = "spa-app",
                clientSecret = "any",
                scopes = "openid",
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidClient>(result.error)
    }

    @Test
    fun `clientCredentials returns token without refresh_token on success`() {
        val result =
            svc.clientCredentials(
                tenantSlug = "acme",
                clientId = "backend-app",
                clientSecret = "secret123",
                scopes = "openid",
            )
        assertIs<OAuthResult.Success<*>>(result)
        val tokenResponse = (result as OAuthResult.Success<*>).value as com.kauth.domain.model.TokenResponse
        assertNotNull(tokenResponse.access_token)
        assertNull(tokenResponse.refresh_token, "M2M tokens must not include a refresh_token")
        assertTrue(auditLog.hasEvent(AuditEventType.TOKEN_ISSUED))
    }

    // =========================================================================
    // refreshTokens
    // =========================================================================

    @Test
    fun `refreshTokens returns InvalidGrant for unknown refresh token`() {
        val result =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = "no-such-token",
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Failure>(result)
        assertIs<OAuthError.InvalidGrant>(result.error)
    }

    @Test
    fun `refreshTokens rotates token — old session is revoked, new session is created`() {
        // First, establish a session via code exchange
        val issueResult =
            svc.issueAuthorizationCode(
                tenantSlug = "acme",
                userId = 10,
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
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
                clientId = "spa-app",
                redirectUri = "https://app.example.com/callback",
                codeVerifier = pkceVerifier,
                clientSecret = null,
            )
        val firstRefreshToken =
            (exchangeResult as OAuthResult.Success<*>)
                .value
                .let { it as com.kauth.domain.model.TokenResponse }
                .refresh_token!!

        // Refresh
        val refreshResult =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = firstRefreshToken,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Success<*>>(refreshResult)

        // Replaying the old refresh token must now fail (rotation = single-use)
        val replayResult =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = firstRefreshToken,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Failure>(replayResult)
        assertIs<OAuthError.InvalidGrant>(replayResult.error)
    }

    // -------------------------------------------------------------------------
    // PKCE helper — mirrors OAuthService.verifyPkce
    // -------------------------------------------------------------------------

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
