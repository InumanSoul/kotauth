package com.kauth.domain.service

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.util.sha256Hex
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

    /** Public client — PKCE required. */
    private val publicClient =
        Application(
            id = ApplicationId(1),
            tenantId = TenantId(1),
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
            id = ApplicationId(2),
            tenantId = TenantId(1),
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
        tokens.reset()

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
                userId = UserId(10),
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
                userId = UserId(10),
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
                userId = UserId(10),
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
                userId = UserId(10),
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
                userId = UserId(10),
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
                tenantId = TenantId(1),
                clientId = publicClient.id,
                userId = UserId(10),
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
                userId = UserId(10),
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
                userId = UserId(10),
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
                userId = UserId(10),
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

        val activeSessions = sessions.findActiveByUser(TenantId(1), UserId(10))
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
                userId = UserId(10),
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

    @Test
    fun `refreshTokens replay of rotated token revokes all user sessions and audits`() {
        val firstRefreshToken = establishSession(client = publicClient)

        val refreshResult =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = firstRefreshToken,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Success<*>>(refreshResult)

        // Attacker replays the rotated token — theft assumption kicks in
        svc.refreshTokens(
            tenantSlug = "acme",
            refreshToken = firstRefreshToken,
            clientId = "spa-app",
        )

        assertTrue(
            sessions.all().filter { it.userId == UserId(10) }.none { it.isActive },
            "Replay of a rotated refresh token must revoke every session for the user",
        )
        assertTrue(
            auditLog.events.any {
                it.eventType == AuditEventType.SESSION_REVOKED &&
                    it.details["reason"] == "refresh_token_replay_detected"
            },
            "Replay must record a refresh_token_replay_detected audit event",
        )
    }

    @Test
    fun `refreshTokens replay after logout does not cascade to other sessions`() {
        val refreshToken = establishSession(client = publicClient)
        val otherSession =
            sessions.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = publicClient.id,
                    accessTokenHash = sha256Hex("other-device-access"),
                    refreshTokenHash = sha256Hex("other-device-refresh"),
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                    refreshExpiresAt = Instant.now().plusSeconds(86400),
                ),
            )

        // Logout-style revocation (no rotation reason)
        val loggedOut = sessions.findByRefreshTokenHash(sha256Hex(refreshToken))!!
        sessions.revoke(loggedOut.id!!)

        // A benign client retry after logout must NOT destroy unrelated sessions
        val result =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = refreshToken,
                clientId = "spa-app",
            )
        assertIs<OAuthResult.Failure>(result)
        assertTrue(
            sessions.findById(otherSession.id!!)!!.isActive,
            "Logout replay must not cascade to the user's other sessions",
        )
    }

    @Test
    fun `refreshTokens requires client_secret for confidential clients`() {
        val refreshToken = establishSession(client = confidentialClient, clientSecret = "secret123")

        // Missing secret → invalid_client
        val noSecret =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = refreshToken,
                clientId = "backend-app",
            )
        assertIs<OAuthResult.Failure>(noSecret)
        assertIs<OAuthError.InvalidClient>(noSecret.error)

        // Wrong secret → invalid_client
        val wrongSecret =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = refreshToken,
                clientId = "backend-app",
                clientSecret = "wrong-secret",
            )
        assertIs<OAuthResult.Failure>(wrongSecret)
        assertIs<OAuthError.InvalidClient>(wrongSecret.error)

        // Correct secret → rotation succeeds
        val ok =
            svc.refreshTokens(
                tenantSlug = "acme",
                refreshToken = refreshToken,
                clientId = "backend-app",
                clientSecret = "secret123",
            )
        assertIs<OAuthResult.Success<*>>(ok)
    }

    /** Runs the full code flow for [client] and returns the issued refresh token. */
    private fun establishSession(
        client: Application,
        clientSecret: String? = null,
    ): String {
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
                clientSecret = clientSecret,
            )
        return (exchangeResult as OAuthResult.Success<*>)
            .value
            .let { it as com.kauth.domain.model.TokenResponse }
            .refresh_token!!
    }

    // =========================================================================
    // introspectToken
    // =========================================================================

    @Test
    fun `introspectToken returns Inactive for unknown token`() {
        val result = introspectAsBackend("unknown-token")
        assertIs<IntrospectionResult.Inactive>(result)
    }

    @Test
    fun `introspectToken returns Unauthorized without client credentials`() {
        val result =
            svc.introspectToken(
                tenantSlug = "acme",
                token = "any-token",
                clientId = null,
                clientSecret = null,
            )
        assertIs<IntrospectionResult.Unauthorized>(result)
    }

    @Test
    fun `introspectToken returns Unauthorized for wrong client secret`() {
        val result =
            svc.introspectToken(
                tenantSlug = "acme",
                token = "any-token",
                clientId = "backend-app",
                clientSecret = "wrong-secret",
            )
        assertIs<IntrospectionResult.Unauthorized>(result)
    }

    @Test
    fun `introspectToken returns Unauthorized for public client credentials`() {
        // RFC 7662: only clients able to hold a secret may introspect
        val result =
            svc.introspectToken(
                tenantSlug = "acme",
                token = "any-token",
                clientId = "spa-app",
                clientSecret = "irrelevant",
            )
        assertIs<IntrospectionResult.Unauthorized>(result)
    }

    private fun introspectAsBackend(token: String) =
        svc.introspectToken(
            tenantSlug = "acme",
            token = token,
            clientId = "backend-app",
            clientSecret = "secret123",
        )

    @Test
    fun `introspectToken returns Inactive when session exists but token cannot be decoded`() {
        // Create a session with a known access token hash
        val accessToken = "valid-access-token"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        // FakeTokenPort.decodeAccessToken returns null by default
        tokens.claimsToReturn = null

        val result = introspectAsBackend(accessToken)
        assertIs<IntrospectionResult.Inactive>(result)
    }

    @Test
    fun `introspectToken returns Active with claims when session and token are valid`() {
        val accessToken = "valid-access-token"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid profile",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        val expiresAt = Instant.now().plusSeconds(3600).epochSecond
        tokens.claimsToReturn =
            AccessTokenClaims(
                sub = "10",
                iss = "https://acme.example.com",
                aud = "spa-app",
                tenantId = TenantId(1),
                username = "alice",
                email = "alice@example.com",
                scopes = listOf("openid", "profile"),
                issuedAt = Instant.now().epochSecond,
                expiresAt = expiresAt,
            )

        val result = introspectAsBackend(accessToken)
        assertIs<IntrospectionResult.Active>(result)
        assertEquals("10", result.sub)
        assertEquals("alice", result.username)
        assertEquals("alice@example.com", result.email)
        assertEquals(listOf("openid", "profile"), result.scopes)
        assertEquals(expiresAt, result.expiresAt)
    }

    // =========================================================================
    // revokeToken
    // =========================================================================

    @Test
    fun `revokeToken - unknown token is a no-op after successful client auth`() {
        val ok = svc.revokeToken("acme", "nonexistent-token", "backend-app", "secret123")
        assertTrue(ok, "Per RFC 7009 unknown tokens still return success")
    }

    @Test
    fun `revokeToken - fails client authentication without credentials`() {
        val accessToken = "access-not-revocable"
        val session =
            sessions.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = publicClient.id,
                    accessTokenHash = sha256Hex(accessToken),
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

        val ok = svc.revokeToken("acme", accessToken, null, null)

        assertEquals(false, ok, "Missing credentials must fail RFC 7009 client auth")
        assertNull(sessions.findById(session.id!!)?.revokedAt, "Session must not be revoked")
    }

    @Test
    fun `revokeToken - revokes session by access token`() {
        val accessToken = "access-to-revoke"
        val session =
            sessions.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = publicClient.id,
                    accessTokenHash = sha256Hex(accessToken),
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

        svc.revokeToken("acme", accessToken, "backend-app", "secret123")

        val revoked = sessions.findById(session.id!!)
        assertNotNull(revoked)
        assertNotNull(revoked.revokedAt, "Session should be revoked")
        assertTrue(auditLog.hasEvent(AuditEventType.TOKEN_REVOKED))
    }

    @Test
    fun `revokeToken - revokes session by refresh token`() {
        val refreshToken = "refresh-to-revoke"
        val session =
            sessions.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = publicClient.id,
                    accessTokenHash = sha256Hex("some-access"),
                    refreshTokenHash = sha256Hex(refreshToken),
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                    refreshExpiresAt = Instant.now().plusSeconds(86400),
                ),
            )

        svc.revokeToken("acme", refreshToken, "backend-app", "secret123")

        val revoked = sessions.findById(session.id!!)
        assertNotNull(revoked)
        assertNotNull(revoked.revokedAt, "Session should be revoked via refresh token")
    }

    // =========================================================================
    // getUserInfo
    // =========================================================================

    @Test
    fun `getUserInfo - returns null for unknown token`() {
        assertNull(svc.getUserInfo("unknown-token"))
    }

    @Test
    fun `getUserInfo - returns null for M2M session (no userId)`() {
        val accessToken = "m2m-access"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = null,
                clientId = confidentialClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

        assertNull(svc.getUserInfo(accessToken))
    }

    @Test
    fun `getUserInfo - returns null for disabled user`() {
        val disabledUser = testUser.copy(id = UserId(20), username = "bob", email = "bob@example.com", enabled = false)
        users.add(disabledUser)
        val accessToken = "disabled-user-access"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(20),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

        assertNull(svc.getUserInfo(accessToken))
    }

    @Test
    fun `getUserInfo - returns user claims for valid token`() {
        val accessToken = "valid-userinfo-token"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid profile",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

        val info = svc.getUserInfo(accessToken)
        assertNotNull(info)
        assertEquals("10", info.sub)
        assertEquals("alice", info.username)
        assertEquals("alice@example.com", info.email)
        assertTrue(info.emailVerified.not(), "testUser has emailVerified=false by default")
        assertEquals("Alice", info.name)
    }

    // =========================================================================
    // endSession
    // =========================================================================

    @Test
    fun `endSession - unknown token is a no-op`() {
        svc.endSession(accessToken = "no-such-token")
        // No exception
    }

    @Test
    fun `endSession - revokes single session`() {
        val accessToken = "session-to-end"
        val session =
            sessions.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = publicClient.id,
                    accessTokenHash = sha256Hex(accessToken),
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
        // Create a second session that should NOT be revoked
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex("other-access"),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

        svc.endSession(accessToken = accessToken)

        assertNotNull(sessions.findById(session.id!!)?.revokedAt, "Target session should be revoked")
        assertEquals(1, sessions.findActiveByUser(TenantId(1), UserId(10)).size, "Other session should remain active")
        assertTrue(auditLog.hasEvent(AuditEventType.SESSION_REVOKED))
    }

    @Test
    fun `endSession - revokeAll revokes all user sessions`() {
        val accessToken = "session-global-logout"
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )
        sessions.save(
            Session(
                tenantId = TenantId(1),
                userId = UserId(10),
                clientId = publicClient.id,
                accessTokenHash = sha256Hex("other-access-2"),
                refreshTokenHash = null,
                scopes = "openid",
                expiresAt = Instant.now().plusSeconds(3600),
            ),
        )

        svc.endSession(accessToken = accessToken, revokeAll = true)

        assertEquals(
            0,
            sessions.findActiveByUser(TenantId(1), UserId(10)).size,
            "All sessions should be revoked for global logout",
        )
    }

    // =========================================================================
    // getJwks
    // =========================================================================

    @Test
    fun `getJwks - delegates to tokenPort and returns empty by default`() {
        val jwks = svc.getJwks(tenantId = TenantId(1))
        assertTrue(jwks.isEmpty())
    }

    @Test
    fun `getJwks - returns configured JWKS from tokenPort`() {
        tokens.jwksToReturn = listOf(mapOf("kty" to "RSA", "kid" to "key-1", "use" to "sig"))
        val jwks = svc.getJwks(tenantId = TenantId(1))
        assertEquals(1, jwks.size)
        assertEquals("RSA", jwks[0]["kty"])
        assertEquals("key-1", jwks[0]["kid"])
    }

    // -------------------------------------------------------------------------
    // PKCE helper — mirrors OAuthService.verifyPkce
    // -------------------------------------------------------------------------

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
