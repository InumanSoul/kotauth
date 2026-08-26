package com.kauth.adapter.web.auth

import com.kauth.config.StaticSocialProviderResolver
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SocialUserProfile
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.util.Pkce
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeSocialProviderPort
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP integration tests for the social-login state parameter.
 *
 * The signed state carries
 * `provider|slug|csrfNonce|timestampMillis|oidcNonce|pkceVerifier|oauthParamsB64`.
 * Most tests forge states directly with [EncryptionService.signCookie] so the callback guard can
 * be exercised without a real provider round-trip; the two round-trip tests instead drive the
 * redirect first and replay the state it produced, which is the only way to catch a reader left
 * on a stale field index.
 */
class SocialLoginRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodeRepo = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val socialAccountRepo = FakeSocialAccountRepository()
    private val idpRepo = FakeIdentityProviderRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()

    private val loginLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val registerLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val tokenLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private val credentialFlowService = mockk<CredentialFlowService>(relaxed = true)

    private val googleAdapter = FakeSocialProviderPort(ProviderKey.GOOGLE)
    private val oktaKey = requireNotNull(ProviderKey.of("okta"))
    private val oktaAdapter = FakeSocialProviderPort(oktaKey)

    private val encryptionService = EncryptionService("test-secret-key")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private fun buildAuthService() =
        AuthService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessionRepo,
        )

    private fun buildOAuthService() =
        OAuthService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            sessionRepository = sessionRepo,
            authCodeRepository = authCodeRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    private fun buildSocialLoginService() =
        SocialLoginService(
            identityProviderRepository = idpRepo,
            socialAccountRepository = socialAccountRepo,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            providerResolver =
                StaticSocialProviderResolver(
                    mapOf(ProviderKey.GOOGLE to googleAdapter, oktaKey to oktaAdapter),
                ),
        )

    private val alice =
        User(
            id = UserId(42),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-pass"),
            enabled = true,
            emailVerified = true,
        )

    private val spaApp =
        Application(
            id = ApplicationId(1),
            tenantId = TenantId(1),
            clientId = "spa-app",
            name = "SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://app.example.com/callback"),
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
        )

    /** The signed state the redirect handed the provider, ready to be replayed on the callback. */
    private fun stateFrom(location: String) = location.substringAfter("state=").encodeURLParameter()

    private fun resetFixtures() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        authCodeRepo.clear()
        sessionRepo.clear()
        socialAccountRepo.clear()
        idpRepo.clear()
        auditLog.clear()
        tokenPort.reset()
        googleAdapter.clear()
        oktaAdapter.clear()
        tenantRepo.add(tenant)
        idpRepo.seed(TenantId(1), "google")
        // The provider exchange fails, so a state that passes the guard still ends on
        // the login page — that is what separates "rejected by the guard" from "let through".
        googleAdapter.shouldFail = true
        oktaAdapter.shouldFail = true
    }

    private fun statePayload(
        provider: String,
        slug: String,
        timestampMillis: Long,
        oauthParamsB64: String = "",
    ): String {
        val csrfNonce =
            UUID
                .randomUUID()
                .toString()
        return "$provider|$slug|$csrfNonce|$timestampMillis|test-oidc-nonce|test-pkce-verifier|$oauthParamsB64"
    }

    private fun signedState(payload: String) = encryptionService.signCookie(payload).encodeURLParameter()

    private fun ApplicationTestBuilder.installSocialRoutes() {
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService = buildAuthService(),
                    oauthService = buildOAuthService(),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = loginLimiter,
                    registerRateLimiter = registerLimiter,
                    tokenRateLimiter = tokenLimiter,
                    credentialFlowService = credentialFlowService,
                    socialLoginService = buildSocialLoginService(),
                    identityProviderRepository = idpRepo,
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }
    }

    @Test
    fun `a callback whose state is older than the max age is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // state stamped 6 minutes ago — beyond the 300_000 ms window
            val stale = System.currentTimeMillis() - 360_000
            val payload = statePayload("google", "acme", stale)
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("State mismatch"),
                "A stale state must be stopped by the state guard, not somewhere downstream",
            )
        }

    @Test
    fun `a callback whose state is within the max age passes the state check`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val fresh = System.currentTimeMillis()
            val payload = statePayload("google", "acme", fresh)
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            // The state check passes; the flow then fails further on at the provider exchange,
            // which is what proves the request got past the guard rather than being stopped by it.
            assertFalse(response.bodyAsText().contains("State mismatch"))
        }

    @Test
    fun `a redirect for an unreserved key with a configured provider row reaches the adapter`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            // "okta" has no compiled-in adapter, which is exactly what Phase 1's RESERVED guard
            // refused. With a configured row it now resolves and the browser leaves for the IdP.
            val response = createClient { followRedirects = false }.get("/t/acme/auth/social/okta/redirect")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"]?.startsWith("https://provider.example.com/auth") == true,
                "An unreserved key with a row must be redirected to its provider, not refused",
            )
        }

    @Test
    fun `a redirect for an unreserved key with no provider row is refused as not configured`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // Same key, no row. The refusal must name the missing configuration: dropping the
            // provider lookup would let this reach the adapter and fail somewhere downstream.
            val response = client.get("/t/acme/auth/social/okta/redirect")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("Social login with this provider is not configured"),
                "An unreserved key with no row must be refused for want of configuration",
            )
        }

    @Test
    fun `a callback for an unreserved key with a configured provider row reaches the adapter`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            val payload = statePayload("okta", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/okta/callback?code=abc&state=${signedState(payload)}")

            // The adapter is configured to fail its exchange, so reaching it produces the provider
            // error rather than the guard's "unsupported_provider" — that is what separates them.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("error occurred communicating with the identity provider"),
                "An unreserved key with a row must reach the adapter, not be refused by a key guard",
            )
        }

    @Test
    fun `a callback for an unreserved key with no provider row is refused as not configured`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val payload = statePayload("okta", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/okta/callback?code=abc&state=${signedState(payload)}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("Social login with this provider is not configured"),
                "An unreserved key with no row must be refused for want of configuration",
            )
        }

    @Test
    fun `the nonce and verifier the redirect issued are the ones the callback replays`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()
            val client = createClient { followRedirects = false }

            val redirect = client.get("/t/acme/auth/social/okta/redirect")
            val issued = requireNotNull(oktaAdapter.bindingAtRedirect, { "the redirect issued no binding" })
            client.get(
                "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
            )

            // Both values travel only inside the signed state, so this is the whole round trip:
            // a reader on a stale index would hand the adapter a csrf nonce or a timestamp.
            assertEquals(
                issued,
                oktaAdapter.bindingAtExchange,
                "The callback must replay the nonce and PKCE verifier the redirect signed into the state",
            )
            assertEquals(
                43,
                issued.codeVerifier.length,
                "A PKCE verifier is 32 random bytes as base64url — RFC 7636 accepts 43 to 128 characters",
            )
            assertTrue(
                Pkce.challengeFor(issued.codeVerifier).isNotBlank(),
                "The verifier must derive an S256 challenge",
            )
        }

    @Test
    fun `every redirect issues its own nonce and its own verifier`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()
            val client = createClient { followRedirects = false }

            client.get("/t/acme/auth/social/okta/redirect")
            val first = requireNotNull(oktaAdapter.bindingAtRedirect)
            client.get("/t/acme/auth/social/okta/redirect")
            val second = requireNotNull(oktaAdapter.bindingAtRedirect)

            // Per request, not per process: a binding minted once and reused would make every
            // round-trip assertion pass while the replay defence protected nothing.
            assertTrue(first.nonce != second.nonce, "Two redirects must not share a nonce")
            assertTrue(first.codeVerifier != second.codeVerifier, "Two redirects must not share a verifier")
        }

    @Test
    fun `a state in the pre-Phase-2 five field format is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // A state signed before the nonce and verifier were added parses far enough to look
            // valid, and its last field would be read as a PKCE verifier. It must not be accepted.
            val csrfNonce = UUID.randomUUID().toString()
            val legacy = "google|acme|$csrfNonce|${System.currentTimeMillis()}|"
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(legacy)}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("State mismatch"))
        }

    @Test
    fun `the OAuth parameters the redirect carried survive the state round trip`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            userRepo.add(alice)
            appRepo.add(spaApp)
            oktaAdapter.shouldFail = false
            oktaAdapter.profileToReturn =
                SocialUserProfile(
                    providerUserId = "okta|42",
                    email = alice.email,
                    name = "Alice",
                    emailVerified = true,
                )
            installSocialRoutes()
            val client = createClient { followRedirects = false }

            val redirect =
                client.get(
                    "/t/acme/auth/social/okta/redirect?response_type=code&client_id=spa-app" +
                        "&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback&state=client-state" +
                        "&code_challenge=client-challenge&code_challenge_method=S256",
                )
            val response =
                client.get(
                    "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
                )

            // The params ride in the last field of the state, whose index moved when the nonce and
            // verifier were added. Read the wrong one and the login still succeeds — it just
            // answers with tokens instead of returning the browser to the client with a code.
            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.startsWith("https://app.example.com/callback?code="),
                "Expected a redirect back to the client with an authorization code, got: $location",
            )
            assertTrue(location.contains("state=client-state"), "The client's own state must come back too")
            assertEquals(
                "client-challenge",
                authCodeRepo.findByCode(location.substringAfter("?code=").substringBefore("&"))?.codeChallenge,
                "The client's own PKCE challenge must be the one stored, not the one we sent the IdP",
            )
        }

    @Test
    fun `a callback whose state names a different provider is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val payload = statePayload("github", "acme", System.currentTimeMillis())
            val response =
                client.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            // Naming the guard, not just the status: the provider comes from the URL segment,
            // not from the state, so a dropped mismatch check would still 400 downstream at the
            // provider exchange — the same code by a different cause.
            assertTrue(response.bodyAsText().contains("State mismatch"))
        }
}
