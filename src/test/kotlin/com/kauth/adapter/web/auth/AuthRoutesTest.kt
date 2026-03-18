package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.RateLimiter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP integration tests for [authRoutes].
 *
 * These tests spin up a Ktor in-memory test engine with the real routing wired
 * to fakes and MockK mocks. No real server, no real DB, no real BCrypt.
 *
 * Focus areas:
 *   1. POST /login — MFA redirect, password-expired redirect, success
 *   2. GET  /mfa-challenge — cookie guard (missing, tampered, valid)
 *   3. GET  /protocol/openid-connect/auth — parameter validation
 *   4. POST /protocol/openid-connect/token — authorization_code exchange
 */
class AuthRoutesTest {
    // -------------------------------------------------------------------------
    // Shared fakes
    // -------------------------------------------------------------------------

    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodeRepo = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()

    // Rate limiters generous enough to never trip during tests
    private val loginLimiter = RateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val registerLimiter = RateLimiter(maxRequests = 1000, windowSeconds = 60)

    // MockK mocks for services that aren't the focus of these tests
    private val selfService = mockk<UserSelfServiceService>(relaxed = true)
    private val mfaService = mockk<MfaService>(relaxed = true)

    private val tenant =
        Tenant(
            id = 1,
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val user =
        User(
            id = 10,
            tenantId = 1,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-pass"),
            enabled = true,
        )

    private val publicApp =
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

    /** PKCE pair used by the token endpoint tests. */
    private val pkceVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val pkceChallenge = sha256Base64Url(pkceVerifier)

    // -------------------------------------------------------------------------
    // Test application builder — avoids repetition across test cases
    // -------------------------------------------------------------------------

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

    private fun resetFixtures() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        authCodeRepo.clear()
        sessionRepo.clear()
        auditLog.clear()
        tenantRepo.add(tenant)
        userRepo.add(user)
        appRepo.add(publicApp)
    }

    // =========================================================================
    // POST /t/{slug}/login — MFA redirect
    // =========================================================================

    @Test
    fun `POST login redirects to mfa-challenge when user has MFA enabled`() =
        testApplication {
            resetFixtures()
            every { mfaService.shouldChallengeMfa(10) } returns true

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                        mfaService = mfaService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/login",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "correct-pass")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location, "Redirect location header must be set")
            assertTrue(location.contains("/mfa-challenge"), "Must redirect to MFA challenge page")

            // KOTAUTH_MFA_PENDING cookie must be set
            val setCookie = response.headers.getAll("Set-Cookie")
            assertNotNull(setCookie)
            assertTrue(
                setCookie.any { it.contains("KOTAUTH_MFA_PENDING") },
                "KOTAUTH_MFA_PENDING cookie must be set on MFA redirect",
            )
        }

    // =========================================================================
    // POST /t/{slug}/login — password expired redirect
    // =========================================================================

    @Test
    fun `POST login redirects to forgot-password with reason=expired for expired password`() =
        testApplication {
            resetFixtures()
            // Seed a user with an expired password
            userRepo.clear()
            userRepo.add(
                user.copy(
                    lastPasswordChangeAt = Instant.now().minusSeconds(100L * 86_400),
                ),
            )
            val expiredTenant = tenant.copy(passwordPolicyMaxAgeDays = 90)
            tenantRepo.clear()
            tenantRepo.add(expiredTenant)
            every { mfaService.shouldChallengeMfa(any()) } returns false

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                        mfaService = mfaService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/login",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "correct-pass")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("forgot-password"), "Must redirect to forgot-password")
            assertTrue(location.contains("reason=expired"), "Must include reason=expired query param")
        }

    // =========================================================================
    // GET /t/{slug}/mfa-challenge — cookie guard
    // =========================================================================

    @Test
    fun `GET mfa-challenge redirects to login when KOTAUTH_MFA_PENDING cookie is absent`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                        // mfaService intentionally omitted
                    )
                }
            }

            // Must not follow redirects — the route redirects to /login which returns 200 HTML.
            // We want to assert on the 302 itself, not the final destination.
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/mfa-challenge")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.endsWith("/login") || location.contains("/login"),
                "Must redirect to login when MFA pending cookie is absent",
            )
        }

    @Test
    fun `GET mfa-challenge redirects to login when cookie signature is invalid`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            // Must not follow redirects — same reason as above.
            val noFollow = createClient { followRedirects = false }

            // Forge a cookie without a valid HMAC — value without signature
            val response =
                noFollow.get("/t/acme/mfa-challenge") {
                    header("Cookie", "KOTAUTH_MFA_PENDING=10|acme|${System.currentTimeMillis()}.INVALIDSIG")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("/login"),
                "Must redirect to login when MFA pending cookie has invalid signature",
            )
        }

    @Test
    fun `GET mfa-challenge shows challenge form when valid signed cookie is present`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            // Build a properly signed cookie
            val cookieValue = EncryptionService.signCookie("10|acme|${System.currentTimeMillis()}")

            val response =
                client.get("/t/acme/mfa-challenge") {
                    header("Cookie", "KOTAUTH_MFA_PENDING=$cookieValue")
                }

            // Must NOT redirect — must render the MFA form
            assertEquals(HttpStatusCode.OK, response.status)
        }

    // =========================================================================
    // GET /t/{slug}/protocol/openid-connect/auth — parameter validation
    // =========================================================================

    @Test
    fun `GET auth endpoint returns 400 for unsupported response_type`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            val response =
                client.get(
                    "/t/acme/protocol/openid-connect/auth" +
                        "?response_type=token&client_id=spa-app" +
                        "&redirect_uri=https://app.example.com/callback",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("unsupported_response_type"))
        }

    @Test
    fun `GET auth endpoint returns 400 when client_id is missing`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            val response =
                client.get(
                    "/t/acme/protocol/openid-connect/auth" +
                        "?response_type=code&redirect_uri=https://app.example.com/callback",
                    // ↑ client_id intentionally missing
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET auth endpoint renders login page for valid OAuth2 params`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            val response =
                client.get(
                    "/t/acme/protocol/openid-connect/auth" +
                        "?response_type=code&client_id=spa-app" +
                        "&redirect_uri=https://app.example.com/callback" +
                        "&scope=openid+profile" +
                        "&code_challenge=$pkceChallenge" +
                        "&code_challenge_method=S256",
                )

            // Must return 200 and render a login form
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("form") || body.contains("login"),
                "Response must contain the login form",
            )
        }

    // =========================================================================
    // POST /t/{slug}/protocol/openid-connect/token — code exchange
    // =========================================================================

    @Test
    fun `POST token endpoint returns JSON token set on valid authorization_code exchange`() =
        testApplication {
            resetFixtures()
            val oauthSvc = buildOAuthService()

            // Pre-issue a valid authorization code (simulates a completed login)
            val issueResult =
                oauthSvc.issueAuthorizationCode(
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

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = oauthSvc,
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/token",
                    formParameters =
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("client_id", "spa-app")
                            append("redirect_uri", "https://app.example.com/callback")
                            append("code_verifier", pkceVerifier)
                        },
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("access_token"), "Response must contain access_token field")
            assertTrue(body.contains("token_type"), "Response must contain token_type field")
        }

    @Test
    fun `POST token endpoint returns 400 for unsupported grant_type`() =
        testApplication {
            resetFixtures()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        selfServiceService = selfService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/token",
                    formParameters =
                        Parameters.build {
                            append("grant_type", "implicit") // not supported
                            append("client_id", "spa-app")
                        },
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun sha256Base64Url(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
