package com.kauth.adapter.web.auth

import com.kauth.config.StaticSocialProviderResolver
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.RateLimiterPort
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
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.net.URLDecoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** A second tenant on the same instance. It configures no identity provider of its own. */
    private val victimTenant =
        Tenant(
            id = TenantId(2),
            slug = "victimco",
            displayName = "Victim Co",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val bob =
        User(
            id = UserId(99),
            tenantId = TenantId(2),
            username = "bob",
            email = "bob@victimco.example",
            fullName = "Bob",
            passwordHash = hasher.hash("bob-pass"),
            enabled = true,
            emailVerified = true,
        )

    private val victimApp =
        Application(
            id = ApplicationId(2),
            tenantId = TenantId(2),
            clientId = "victim-spa",
            name = "Victim SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://victim.example.com/callback"),
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

    /** The binding cookie's wire name for [provider]: one name per provider, not per tenant. */
    private fun stateCookieName(provider: String) = "${STATE_COOKIE}_$provider"

    /**
     * The browser-binding cookie the redirect would have set for [payload]: its csrfNonce, signed.
     * A forged state needs it because the callback refuses any state not bound to this browser.
     */
    private fun HttpRequestBuilder.bindStateCookie(payload: String) {
        val parts = payload.split("|")
        header("Cookie", "${stateCookieName(parts[0])}=${encryptionService.signCookie(parts[2])}")
    }

    private fun ApplicationTestBuilder.installSocialRoutes(
        socialLimiter: RateLimiterPort = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60),
        baseUrl: String = "",
        trustedProxy: Boolean = false,
    ) {
        application {
            install(ContentNegotiation) { json() }
            if (trustedProxy) install(XForwardedHeaders)
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
                    socialRateLimiter = socialLimiter,
                    baseUrl = baseUrl,
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
                client.get("/t/acme/auth/social/okta/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

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
                client.get("/t/acme/auth/social/okta/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

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
            val client =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }

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
    fun `a state carrying more fields than the format defines is rejected`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // An eighth field means the payload is not the format this reader was written for.
            // Truncating to the first seven would accept it and read every field as if it were.
            val payload = "${statePayload("google", "acme", System.currentTimeMillis())}|extra"
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

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
            val client =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }

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

    @Test
    fun `a callback presenting a validly signed state with no cookie is refused`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // The login-CSRF attack: the state is ours, freshly minted, and names the right
            // provider and slug — an attacker gets one by driving /redirect themselves. Only the
            // absent cookie separates it from a real flow, and it must be enough to stop it.
            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            // Naming the guard: the provider exchange 400s too, so the status alone proves nothing.
            assertTrue(
                response.bodyAsText().contains("did not start in this browser"),
                "An unbound state must be stopped by the browser-binding guard, not somewhere downstream",
            )
        }

    @Test
    fun `a callback whose cookie holds a different nonce is refused`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // A cookie from some other flow of the victim's own: signed by us, and still not the
            // nonce this state carries. Presence of a cookie may not stand in for a match.
            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    header(
                        "Cookie",
                        "${stateCookieName("google")}=${encryptionService.signCookie(UUID.randomUUID().toString())}",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("did not start in this browser"),
                "A cookie naming a different nonce must be refused, not merely counted as present",
            )
        }

    @Test
    fun `a callback whose cookie is not signed by us is refused`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // The cookie is HttpOnly, but a subdomain can still write one. An unsigned value
            // carrying the right nonce must not satisfy the guard.
            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    header("Cookie", "${stateCookieName("google")}=${payload.split("|")[2]}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("did not start in this browser"))
        }

    @Test
    fun `a callback whose cookie matches the state nonce is let through`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

            // The guard passes and the flow fails further on at the provider exchange — a guard
            // that refused everything would satisfy the three tests above and break login.
            assertFalse(response.bodyAsText().contains("did not start in this browser"))
            assertTrue(
                response.bodyAsText().contains("error occurred communicating with the identity provider"),
                "A bound state must reach the provider exchange",
            )
        }

    @Test
    fun `the redirect sets the binding cookie HttpOnly path-scoped and SameSite Lax`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val response = createClient { followRedirects = false }.get("/t/acme/auth/social/google/redirect")

            val cookie =
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .firstOrNull { it.startsWith("${stateCookieName("google")}=") }
            assertNotNull(cookie, "The redirect must set the binding cookie; without it no callback can bind")
            assertTrue(cookie.contains("HttpOnly"), "Script must not be able to read the binding: $cookie")
            // Lax, not Strict: the callback arrives as a top-level navigation from the IdP, which
            // Strict would strip — the guard would then refuse every real login.
            assertTrue(cookie.contains("SameSite=Lax"), "Expected SameSite=Lax, got: $cookie")
            assertTrue(cookie.contains("Path=/t/acme/auth/social"), "Expected a tenant-scoped path, got: $cookie")
        }

    @Test
    fun `the callback clears the binding cookie once the state is consumed`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

            val cleared =
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .firstOrNull { it.startsWith("${stateCookieName("google")}=") }
            assertNotNull(cleared, "The consumed binding must be cleared, not left for a second callback")
            assertTrue(
                cleared.contains("Max-Age=0"),
                "Expected the binding cookie to be expired, got: $cleared",
            )
        }

    @Test
    fun `a redirect flood is refused once the per-IP limit is spent`() {
        testApplication {
            resetFixtures()
            installSocialRoutes(InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60))
            val client = createClient { followRedirects = false }

            repeat(2) { assertEquals(HttpStatusCode.Found, client.get("/t/acme/auth/social/google/redirect").status) }
            val throttled = client.get("/t/acme/auth/social/google/redirect")

            // /redirect performs a blocking outbound fetch for an OIDC row and needs no
            // authentication, so an unbounded loop here amplifies traffic at the issuer.
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("60", throttled.headers["Retry-After"])
        }
    }

    @Test
    fun `the callback spends the same per-IP budget as the redirect`() {
        testApplication {
            resetFixtures()
            installSocialRoutes(InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60))
            val client = createClient { followRedirects = false }

            repeat(2) { client.get("/t/acme/auth/social/google/redirect") }
            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val throttled =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}") {
                    bindStateCookie(payload)
                }

            // The callback exchanges a code at the issuer, so leaving it outside the budget would
            // leave half of the outbound path unthrottled.
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        }
    }

    /**
     * Every social cookie the response set, as one `Cookie` header — the whole set the browser
     * that drove the flow would hold, so a replay is tested against the cookies the attacker
     * really has rather than against a hand-picked one.
     */
    private fun socialCookieHeader(response: HttpResponse): String =
        response.headers
            .getAll("Set-Cookie")
            .orEmpty()
            .filter { it.contains("KOTAUTH_SOCIAL_PENDING") }
            .joinToString("; ") { it.substringBefore(";") }

    @Test
    fun `a pending registration cookie minted at one tenant is refused at another`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            tenantRepo.add(victimTenant)
            userRepo.add(bob)
            appRepo.add(victimApp)
            // The attacker administers acme and points it at an IdP they run, which asserts an
            // address they do not own. Unknown in acme, so the callback answers NeedsRegistration.
            oktaAdapter.shouldFail = false
            oktaAdapter.profileToReturn =
                SocialUserProfile(
                    providerUserId = "okta|attacker",
                    email = bob.email,
                    name = "Bob",
                    emailVerified = true,
                )
            installSocialRoutes()
            val attacker =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }

            val redirect =
                attacker.get(
                    "/t/acme/auth/social/okta/redirect?response_type=code&client_id=victim-spa" +
                        "&redirect_uri=https%3A%2F%2Fvictim.example.com%2Fcallback&state=attacker-state" +
                        "&code_challenge=attacker-challenge&code_challenge_method=S256",
                )
            val callback =
                attacker.get(
                    "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
                )
            val stolen = socialCookieHeader(callback)
            assertTrue(stolen.isNotEmpty(), "The attacker's own tenant must mint a pending cookie for this to test")

            // Replayed at a tenant that configured no provider and never took part. Cookie path
            // scoping is browser-side only, so a non-browser client sends it wherever it likes.
            val replay =
                createClient { followRedirects = false }
                    .post("/t/victimco/auth/social/complete-registration") {
                        header("Cookie", stolen)
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=bobtakeover")
                    }

            assertTrue(
                replay.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .none { it.contains("KOTAUTH_SSO=") },
                "A cross-tenant replay must leave no session behind: ${replay.headers.getAll("Set-Cookie")}",
            )
            assertFalse(
                replay.headers["Location"].orEmpty().startsWith("https://victim.example.com/callback"),
                "The victim's client must not be handed an authorization code for this replay",
            )
            assertTrue(
                socialAccountRepo.all().none { it.tenantId == TenantId(2) },
                "No provider identity may be linked into the victim's tenant",
            )
            assertTrue(authCodeRepo.all().isEmpty(), "No authorization code may be issued for the victim's tenant")
            assertTrue(sessionRepo.findByUserId(UserId(99)).isEmpty(), "The victim must not be signed in")
        }

    /** Drives redirect + callback at [slug] until the pending cookie exists, and returns it. */
    private suspend fun ApplicationTestBuilder.pendingCookieFor(
        slug: String,
        email: String,
    ): String {
        oktaAdapter.shouldFail = false
        oktaAdapter.profileToReturn =
            SocialUserProfile(
                providerUserId = "okta|$email",
                email = email,
                name = "New Comer",
                emailVerified = true,
            )
        val browser =
            createClient {
                followRedirects = false
                install(HttpCookies)
            }
        val redirect = browser.get("/t/$slug/auth/social/okta/redirect")
        val callback =
            browser.get(
                "/t/$slug/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
            )
        return socialCookieHeader(callback)
    }

    @Test
    fun `a pending cookie completes registration at the tenant that minted it`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            // The guard has to let the real flow through: one that refused every replay would
            // satisfy the cross-tenant test above and break social registration outright.
            val cookies = pendingCookieFor("acme", "newcomer@acme.com")
            val completed =
                createClient { followRedirects = false }
                    .post("/t/acme/auth/social/complete-registration") {
                        header("Cookie", cookies)
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=newcomer")
                    }

            assertEquals(HttpStatusCode.Found, completed.status)
            assertEquals("/t/acme/account/login", completed.headers["Location"])
            assertNotNull(
                userRepo.findByEmail(TenantId(1), "newcomer@acme.com"),
                "A pending cookie presented at its own tenant must still register the user",
            )
        }

    @Test
    fun `a pending cookie presented without its binding cookie is refused`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            // Right tenant, our signature, freshly minted — only the binding is missing. A cookie
            // planted in someone else's browser must not complete as if they had begun the flow.
            val pendingOnly =
                pendingCookieFor("acme", "newcomer@acme.com")
                    .split("; ")
                    .first { it.startsWith("KOTAUTH_SOCIAL_PENDING=") }
            val response =
                createClient { followRedirects = false }
                    .post("/t/acme/auth/social/complete-registration") {
                        header("Cookie", pendingOnly)
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=newcomer")
                    }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"].orEmpty().startsWith("/t/acme/authorize?error="),
                "An unbound pending cookie must be sent back to the start, got: ${response.headers["Location"]}",
            )
            assertTrue(userRepo.findByEmail(TenantId(1), "newcomer@acme.com") == null, "No user may be created")
        }

    @Test
    fun `a rejected authorization request leaves no SSO session behind`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            // The completion names a client this tenant does not have, so code issuance fails.
            // A session cookie written before that check survives the refusal.
            oktaAdapter.shouldFail = false
            oktaAdapter.profileToReturn =
                SocialUserProfile(
                    providerUserId = "okta|newcomer",
                    email = "newcomer@acme.com",
                    name = "New Comer",
                    emailVerified = true,
                )
            val browser =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }
            val redirect =
                browser.get(
                    "/t/acme/auth/social/okta/redirect?response_type=code&client_id=ghost-app" +
                        "&redirect_uri=https%3A%2F%2Fghost.example.com%2Fcallback" +
                        "&code_challenge=c&code_challenge_method=S256",
                )
            val callback =
                browser.get(
                    "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
                )
            val completed =
                createClient { followRedirects = false }
                    .post("/t/acme/auth/social/complete-registration") {
                        header("Cookie", socialCookieHeader(callback))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=newcomer")
                    }

            assertEquals(HttpStatusCode.BadRequest, completed.status)
            assertTrue(
                completed.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .none { it.contains("KOTAUTH_SSO=") },
                "A failed code issuance must not leave a session: ${completed.headers.getAll("Set-Cookie")}",
            )
        }

    @Test
    fun `the pending cookies are HttpOnly path-scoped and SameSite Lax`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()
            oktaAdapter.shouldFail = false
            oktaAdapter.profileToReturn =
                SocialUserProfile(
                    providerUserId = "okta|newcomer",
                    email = "newcomer@acme.com",
                    name = "New Comer",
                    emailVerified = true,
                )
            val browser =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }

            val redirect = browser.get("/t/acme/auth/social/okta/redirect")
            val callback =
                browser.get(
                    "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
                )

            // Two halves of one flow disagreeing on cookie attributes is how the weaker half
            // comes to look deliberate: the pending pair is hardened like the state cookie.
            val pendingCookies =
                callback.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .filter { it.contains("KOTAUTH_SOCIAL_PENDING") }
            assertEquals(2, pendingCookies.size, "Expected the pending cookie and its binding, got: $pendingCookies")
            pendingCookies.forEach {
                assertTrue(it.contains("HttpOnly"), "Script must not read the pending pair: $it")
                assertTrue(it.contains("SameSite=Lax"), "Expected SameSite=Lax, got: $it")
                assertTrue(it.contains("Path=/t/acme/auth/social"), "Expected a tenant-scoped path, got: $it")
            }
        }

    @Test
    fun `over https the social cookies are Host-prefixed and Secure`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes(baseUrl = "https://id.example.com")
            oktaAdapter.shouldFail = false
            oktaAdapter.profileToReturn =
                SocialUserProfile(
                    providerUserId = "okta|newcomer",
                    email = "newcomer@acme.com",
                    name = "New Comer",
                    emailVerified = true,
                )
            // No cookie storage: a test client will not send a Secure cookie over the test
            // harness's plain http, so every cookie is replayed by hand here.
            val browser = createClient { followRedirects = false }

            val redirect = browser.get("/t/acme/auth/social/okta/redirect")
            val stateCookie =
                redirect.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .single { it.contains(STATE_COOKIE) }

            // A server cannot tell a host-only cookie from one a sibling subdomain set with
            // Domain=; __Host- forbids Domain and browsers enforce it. It costs Path=/.
            assertTrue(
                stateCookie.startsWith("__Host-${stateCookieName("okta")}="),
                "Expected a __Host- name, got: $stateCookie",
            )
            assertTrue(stateCookie.contains("Secure"), "__Host- is dropped without Secure: $stateCookie")
            assertTrue(stateCookie.contains("Path=/;"), "__Host- requires Path=/, got: $stateCookie")

            val callback =
                browser.get(
                    "/t/acme/auth/social/okta/callback?code=abc&state=${stateFrom(redirect.headers["Location"]!!)}",
                ) {
                    header("Cookie", stateCookie.substringBefore(";"))
                }
            val pendingCookies =
                callback.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .filter { it.contains("KOTAUTH_SOCIAL_PENDING") }
            assertEquals(2, pendingCookies.size, "Expected the pending cookie and its binding, got: $pendingCookies")
            pendingCookies.forEach {
                assertTrue(it.startsWith("__Host-"), "Expected a __Host- name, got: $it")
                assertTrue(it.contains("Secure"), "__Host- is dropped without Secure: $it")
                assertTrue(it.contains("Path=/;"), "__Host- requires Path=/, got: $it")
            }

            // And the prefixed names are the ones read back: the flow must still complete.
            val completed =
                createClient { followRedirects = false }
                    .post("/t/acme/auth/social/complete-registration") {
                        header("Cookie", socialCookieHeader(callback))
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=newcomer")
                    }
            assertEquals("/t/acme/account/login", completed.headers["Location"])
        }

    @Test
    fun `over plain http the state cookie keeps its unprefixed path-scoped name`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            val redirect = createClient { followRedirects = false }.get("/t/acme/auth/social/google/redirect")
            val stateCookie =
                redirect.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .single { it.contains(STATE_COOKIE) }

            // __Host- requires Secure, which http cannot set — a prefixed name here would be
            // dropped by the browser and every callback would fail to bind on `make run`.
            assertTrue(
                stateCookie.startsWith("${stateCookieName("google")}="),
                "Expected the bare name, got: $stateCookie",
            )
            assertFalse(stateCookie.contains("Secure"), "Nothing may set Secure over http: $stateCookie")
        }

    @Test
    fun `the throttle keys on the forwarded client address, not the proxy connection`() =
        testApplication {
            resetFixtures()
            installSocialRoutes(InMemoryRateLimiter(maxRequests = 1, windowSeconds = 60), trustedProxy = true)
            val client = createClient { followRedirects = false }

            val first =
                client.get("/t/acme/auth/social/google/redirect") { header("X-Forwarded-For", "203.0.113.1") }
            val second =
                client.get("/t/acme/auth/social/google/redirect") { header("X-Forwarded-For", "203.0.113.2") }

            // Every request behind the shipped Caddy topology shares one connection address, so
            // keying on it makes this limiter a deployment-wide cap of a few sign-ins a minute.
            assertEquals(HttpStatusCode.Found, first.status)
            assertEquals(
                HttpStatusCode.Found,
                second.status,
                "A second client behind the same proxy must have its own budget",
            )
        }

    @Test
    fun `a non-IP forwarded value does not open a fresh throttle bucket`() =
        testApplication {
            resetFixtures()
            installSocialRoutes(InMemoryRateLimiter(maxRequests = 1, windowSeconds = 60), trustedProxy = true)
            val client = createClient { followRedirects = false }

            // `remoteHost` takes any X-Forwarded-For value, a hostname included; `remoteAddress`
            // takes only an IP literal and otherwise keeps the socket address. This pins that
            // difference, which is the only part of the remoteHost/remoteAddress choice a test can
            // observe — the reverse-DNS lookup itself happens inside Netty's connection point and
            // the test engine has none. See the report for why it is not pinned here.
            val first = client.get("/t/acme/auth/social/google/redirect") { header("X-Forwarded-For", "one.example") }
            val second = client.get("/t/acme/auth/social/google/redirect") { header("X-Forwarded-For", "two.example") }

            assertEquals(HttpStatusCode.Found, first.status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                second.status,
                "A name in X-Forwarded-For must not be trusted to key the throttle",
            )
        }

    /** An operator-chosen label, which the routes must prefer over the key's title case. */
    private fun seedLabelledOkta() =
        idpRepo.add(
            IdentityProvider(
                tenantId = TenantId(1),
                provider = oktaKey,
                clientId = "client-okta",
                clientSecret = "secret-okta",
                displayName = "Acme Workforce SSO",
            ),
        )

    @Test
    fun `the callback error page names the provider by its configured label`() =
        testApplication {
            resetFixtures()
            seedLabelledOkta()
            installSocialRoutes()

            val response = client.get("/t/acme/auth/social/okta/callback?error=access_denied")

            // The route looks the row up by key; the view is handed whatever it returns. Title
            // casing the key would render "Okta" here and every assertion on the view still pass.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            // The whole sentence, not just the label: the same page lists the tenant's providers
            // by their labels, so a bare `contains` would pass on the sign-in button alone.
            assertTrue(
                response.bodyAsText().contains("Login with Acme Workforce SSO was cancelled or failed."),
                "The cancelled-login page must name the operator's label, got: ${response.bodyAsText()}",
            )
        }

    @Test
    fun `the social registration page names the provider by its configured label`() =
        testApplication {
            resetFixtures()
            seedLabelledOkta()
            installSocialRoutes()

            val cookies = pendingCookieFor("acme", "newcomer@acme.com")
            val page =
                createClient { followRedirects = false }
                    .get("/t/acme/auth/social/complete-registration") { header("Cookie", cookies) }

            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(
                page.bodyAsText().contains("You're signing in with Acme Workforce SSO."),
                "The registration page must name the operator's label, got: ${page.bodyAsText()}",
            )
        }

    @Test
    fun `two sign-ins begun in one browser keep separate bindings`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()
            val browser =
                createClient {
                    followRedirects = false
                    install(HttpCookies)
                }

            val google = browser.get("/t/acme/auth/social/google/redirect")
            val okta = browser.get("/t/acme/auth/social/okta/redirect")

            // One name per tenant means the second sign-in overwrites the first, and the first
            // callback then fails to bind — a user with two tabs open cannot log in.
            assertTrue(
                google.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .any { it.startsWith("${stateCookieName("google")}=") },
            )
            assertTrue(
                okta.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .any { it.startsWith("${stateCookieName("okta")}=") },
            )
            val completed =
                browser.get(
                    "/t/acme/auth/social/google/callback?code=abc&state=${stateFrom(google.headers["Location"]!!)}",
                )
            assertFalse(
                completed.bodyAsText().contains("did not start in this browser"),
                "The first flow's binding must survive a second flow begun in the same browser",
            )
        }

    @Test
    fun `a refused callback leaves the in-flight binding in place`() =
        testApplication {
            resetFixtures()
            installSocialRoutes()

            // Anyone can mint a state by driving /redirect themselves and feed the resulting
            // callback URL to a browser mid-login. Clearing on refusal would let that cancel it.
            val payload = statePayload("google", "acme", System.currentTimeMillis())
            val response =
                client.get("/t/acme/auth/social/google/callback?code=abc&state=${signedState(payload)}")

            assertTrue(response.bodyAsText().contains("did not start in this browser"))
            assertTrue(
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .none { it.startsWith("${stateCookieName("google")}=") },
                "A refused callback must not touch the binding: ${response.headers.getAll("Set-Cookie")}",
            )
        }

    @Test
    fun `a pending cookie carrying more fields than the format defines is refused`() =
        testApplication {
            resetFixtures()
            idpRepo.seed(TenantId(1), "okta")
            installSocialRoutes()

            // Same shape as the state's own field-count guard: a payload of another shape is not
            // this format, and reading it field by field would take each one for something else.
            val cookies = pendingCookieFor("acme", "newcomer@acme.com")
            val signed =
                URLDecoder.decode(
                    cookies.substringAfter("KOTAUTH_SOCIAL_PENDING=").substringBefore(";"),
                    Charsets.UTF_8,
                )
            val forged = encryptionService.signCookie(signed.substringBeforeLast(".") + "|extra")
            // The real binding cookie rides along, so the field count is the only thing left to
            // refuse it — otherwise the browser-binding guard would answer for this test.
            val binding = cookies.split("; ").first { it.startsWith("KOTAUTH_SOCIAL_PENDING_BINDING=") }
            val response =
                createClient { followRedirects = false }
                    .post("/t/acme/auth/social/complete-registration") {
                        header("Cookie", "KOTAUTH_SOCIAL_PENDING=${forged.encodeURLParameter()}; $binding")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("username=newcomer")
                    }

            assertTrue(
                response.headers["Location"].orEmpty().startsWith("/t/acme/authorize?error="),
                "A payload of another shape must be refused, got: ${response.headers["Location"]}",
            )
            assertTrue(userRepo.findByEmail(TenantId(1), "newcomer@acme.com") == null, "No user may be created")
        }

    companion object {
        /** The wire name of the cookie that binds a social-login state to the browser that began it. */
        private const val STATE_COOKIE = "KOTAUTH_SOCIAL_STATE"
    }
}
