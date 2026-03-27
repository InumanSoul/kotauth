package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
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
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.bearerAuth
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
    private val loginLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val registerLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val tokenLimiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    // MockK mocks for services that aren't the focus of these tests
    private val selfService = mockk<UserSelfServiceService>(relaxed = true)
    private val mfaService = mockk<MfaService>(relaxed = true)

    private val encryptionService = EncryptionService("test-secret-key")
    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val user =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-pass"),
            enabled = true,
        )

    private val publicApp =
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

    /** PKCE pair used by the token endpoint tests. */
    private val pkceVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val pkceChallenge = sha256Base64Url(pkceVerifier)

    /**
     * Builds a valid signed KOTAUTH_AUTH_CONTEXT cookie value for use in POST /authorize tests.
     * The payload format mirrors [ApplicationCall.setAuthContextCookie] in AuthHelpers.kt.
     */
    private fun buildAuthContextCookie(
        responseType: String = "code",
        clientId: String = "spa-app",
        redirectUri: String = "https://app.example.com/callback",
        scope: String = "openid",
        state: String? = null,
        codeChallenge: String? = null,
        codeChallengeMethod: String? = null,
        nonce: String? = null,
    ): String {
        val payload =
            listOf(
                responseType,
                clientId,
                redirectUri,
                scope,
                state ?: "",
                codeChallenge ?: "",
                codeChallengeMethod ?: "",
                nonce ?: "",
                System.currentTimeMillis().toString(),
            ).joinToString("|")
        return encryptionService.signCookie(payload)
    }

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
        tokenPort.reset()
        tenantRepo.add(tenant)
        userRepo.add(user)
        appRepo.add(publicApp)
    }

    // =========================================================================
    // POST /t/{slug}/authorize — MFA redirect
    // =========================================================================

    @Test
    fun `POST authorize redirects to mfa-challenge when user has MFA enabled`() =
        testApplication {
            resetFixtures()
            every { mfaService.shouldChallengeMfa(UserId(10)) } returns true

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
                        selfServiceService = selfService,
                        mfaService = mfaService,
                        encryptionService = encryptionService,
                    )
                }
            }

            // POST /authorize reads OAuth context from the signed cookie, not form fields.
            val authContextCookie =
                buildAuthContextCookie(
                    clientId = "spa-app",
                    redirectUri = "https://app.example.com/callback",
                )

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/authorize",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "correct-pass")
                        },
                ) {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=$authContextCookie")
                }

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
    // POST /t/{slug}/authorize — password expired redirect
    // =========================================================================

    @Test
    fun `POST authorize redirects to forgot-password with reason=expired for expired password`() =
        testApplication {
            resetFixtures()
            // Seed a user with an expired password
            userRepo.clear()
            userRepo.add(
                user.copy(
                    lastPasswordChangeAt = Instant.now().minusSeconds(100L * 86_400),
                ),
            )
            val expiredTenant = tenant.copy(securityConfig = tenant.securityConfig.copy(passwordMaxAgeDays = 90))
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        mfaService = mfaService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val authContextCookie =
                buildAuthContextCookie(
                    clientId = "spa-app",
                    redirectUri = "https://app.example.com/callback",
                )

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/authorize",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "correct-pass")
                        },
                ) {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=$authContextCookie")
                }

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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        // mfaService intentionally omitted
                        encryptionService = encryptionService,
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
                location.endsWith("/authorize") || location.contains("/authorize"),
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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
                location.contains("/authorize"),
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            // Build a properly signed cookie
            val cookieValue = encryptionService.signCookie("10|acme|${System.currentTimeMillis()}")

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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = oauthSvc,
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = registerLimiter,
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
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

    // =========================================================================
    // GET /t/{slug}/.well-known/openid-configuration — OIDC discovery
    // =========================================================================

    @Test
    fun `GET openid-configuration returns 200 with all required OIDC fields as JSON`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response = client.get("/t/acme/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.headers["Content-Type"]?.contains("application/json") == true,
                "Content-Type must be application/json",
            )

            val body = response.bodyAsText()
            // Required OIDC discovery fields
            assertTrue(body.contains("\"issuer\""), "Must contain issuer")
            assertTrue(body.contains("\"authorization_endpoint\""), "Must contain authorization_endpoint")
            assertTrue(body.contains("\"token_endpoint\""), "Must contain token_endpoint")
            assertTrue(body.contains("\"jwks_uri\""), "Must contain jwks_uri")
            assertTrue(body.contains("\"userinfo_endpoint\""), "Must contain userinfo_endpoint")
            // Array fields must serialize as JSON arrays, not as raw strings —
            // this is the direct regression guard for the Map<String, Any> serialization bug
            assertTrue(
                body.contains("\"response_types_supported\":[") ||
                    body.contains("\"response_types_supported\": ["),
                "response_types_supported must be a JSON array",
            )
            assertTrue(
                body.contains("\"grant_types_supported\":[") ||
                    body.contains("\"grant_types_supported\": ["),
                "grant_types_supported must be a JSON array",
            )
        }

    @Test
    fun `GET openid-configuration returns 404 for unknown tenant slug`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response = client.get("/t/ghost/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("tenant_not_found"))
        }

    @Test
    fun `GET openid-configuration uses custom issuerUrl when configured on tenant`() =
        testApplication {
            val customIssuer = "https://auth.custom.example.com"
            tenantRepo.clear()
            tenantRepo.add(tenant.copy(issuerUrl = customIssuer))
            userRepo.clear()
            userRepo.add(user)
            appRepo.clear()
            appRepo.add(publicApp)

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response = client.get("/t/acme/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"issuer\":\"$customIssuer\"") ||
                    body.contains("\"issuer\": \"$customIssuer\""),
                "issuer must equal the tenant's custom issuerUrl",
            )
            // All derived endpoints must also be rooted at the custom issuer
            assertTrue(
                body.contains(customIssuer),
                "Derived endpoint URLs must use the custom issuer as their base",
            )
        }

    @Test
    fun `GET openid-configuration derives issuer from request when tenant issuerUrl is null`() =
        testApplication {
            // Default tenant fixture already has issuerUrl = null
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response = client.get("/t/acme/.well-known/openid-configuration")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Issuer must include the tenant slug path segment
            assertTrue(
                body.contains("/t/acme"),
                "Derived issuer must contain the tenant slug path segment",
            )
            // Derived endpoints must be consistent with the issuer
            assertTrue(
                body.contains("/t/acme/protocol/openid-connect/token"),
                "token_endpoint must be derived from the fallback issuer",
            )
        }

    // =========================================================================
    // POST /t/{slug}/protocol/openid-connect/revoke — RFC 7009
    // =========================================================================

    @Test
    fun `POST revoke returns 200 for a valid access token`() =
        testApplication {
            resetFixtures()
            val accessToken = "test-access-token-revoke"
            val hash = sha256Hex(accessToken)
            sessionRepo.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = ApplicationId(1),
                    accessTokenHash = hash,
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/revoke",
                    formParameters = Parameters.build { append("token", accessToken) },
                )

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST revoke returns 200 even for unknown token (RFC 7009 compliance)`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/revoke",
                    formParameters = Parameters.build { append("token", "completely-unknown-token") },
                )

            assertEquals(HttpStatusCode.OK, response.status, "RFC 7009: always return 200")
        }

    @Test
    fun `POST revoke returns 400 when token param is missing`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/revoke",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // =========================================================================
    // POST /t/{slug}/protocol/openid-connect/introspect — RFC 7662
    // =========================================================================

    @Test
    fun `POST introspect returns active=true for a valid session with claims`() =
        testApplication {
            resetFixtures()
            val accessToken = "test-access-token-introspect"
            val hash = sha256Hex(accessToken)
            sessionRepo.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = ApplicationId(1),
                    accessTokenHash = hash,
                    refreshTokenHash = null,
                    scopes = "openid profile",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
            tokenPort.claimsToReturn =
                AccessTokenClaims(
                    sub = "10",
                    iss = "http://localhost/t/acme",
                    aud = "spa-app",
                    tenantId = TenantId(1),
                    username = "alice",
                    email = "alice@example.com",
                    scopes = listOf("openid", "profile"),
                    issuedAt = Instant.now().epochSecond,
                    expiresAt = Instant.now().plusSeconds(3600).epochSecond,
                    realmRoles = emptyList(),
                )

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/introspect",
                    formParameters = Parameters.build { append("token", accessToken) },
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"active\":true") || body.contains("\"active\": true"))
            assertTrue(body.contains("\"sub\":\"10\"") || body.contains("\"sub\": \"10\""))
        }

    @Test
    fun `POST introspect returns active=false for unknown token`() =
        testApplication {
            resetFixtures()
            tokenPort.claimsToReturn = null

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/introspect",
                    formParameters = Parameters.build { append("token", "unknown-token") },
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"active\":false") || body.contains("\"active\": false"))
        }

    // =========================================================================
    // GET /t/{slug}/protocol/openid-connect/userinfo — OIDC Core §5.3
    // =========================================================================

    @Test
    fun `GET userinfo returns user claims for valid bearer token`() =
        testApplication {
            resetFixtures()
            val accessToken = "test-access-token-userinfo"
            val hash = sha256Hex(accessToken)
            sessionRepo.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = ApplicationId(1),
                    accessTokenHash = hash,
                    refreshTokenHash = null,
                    scopes = "openid profile",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.get("/t/acme/protocol/openid-connect/userinfo") {
                    bearerAuth(accessToken)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("alice"), "Must contain username")
            assertTrue(body.contains("alice@example.com"), "Must contain email")
        }

    @Test
    fun `GET userinfo returns 401 when no bearer token is provided`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response = client.get("/t/acme/protocol/openid-connect/userinfo")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET userinfo returns 401 for expired session`() =
        testApplication {
            resetFixtures()
            val accessToken = "test-access-token-expired"
            val hash = sha256Hex(accessToken)
            sessionRepo.save(
                Session(
                    tenantId = TenantId(1),
                    userId = UserId(10),
                    clientId = ApplicationId(1),
                    accessTokenHash = hash,
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().minusSeconds(3600),
                ),
            )

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
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.get("/t/acme/protocol/openid-connect/userinfo") {
                    bearerAuth(accessToken)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // =========================================================================
    // POST /t/{slug}/register — registration flow
    // =========================================================================

    @Test
    fun `POST register redirects to portal login on success when no OAuth context`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/register",
                    formParameters =
                        Parameters.build {
                            append("username", "newuser")
                            append("email", "newuser@example.com")
                            append("fullName", "New User")
                            append("password", "StrongP@ss123")
                            append("confirmPassword", "StrongP@ss123")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("/account/login"),
                "Without OAuth context, must redirect to portal login, got: $location",
            )
        }

    @Test
    fun `POST register returns 422 for duplicate username`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/register",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("email", "different@example.com")
                            append("fullName", "Alice Clone")
                            append("password", "StrongP@ss123")
                            append("confirmPassword", "StrongP@ss123")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("already taken"))
        }

    @Test
    fun `POST register returns 422 for mismatched passwords`() =
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
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            val response =
                client.submitForm(
                    url = "/t/acme/register",
                    formParameters =
                        Parameters.build {
                            append("username", "newuser2")
                            append("email", "newuser2@example.com")
                            append("fullName", "New User 2")
                            append("password", "StrongP@ss123")
                            append("confirmPassword", "DifferentPass456")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST register returns 429 when rate limited`() =
        testApplication {
            resetFixtures()
            val tightRegisterLimiter = InMemoryRateLimiter(maxRequests = 1, windowSeconds = 60)

            application {
                install(ContentNegotiation) { json() }
                routing {
                    authRoutes(
                        authService = buildAuthService(),
                        oauthService = buildOAuthService(),
                        tenantRepository = tenantRepo,
                        loginRateLimiter = loginLimiter,
                        registerRateLimiter = tightRegisterLimiter,
                        tokenRateLimiter = tokenLimiter,
                        selfServiceService = selfService,
                        encryptionService = encryptionService,
                    )
                }
            }

            // First request consumes the quota
            client.submitForm(
                url = "/t/acme/register",
                formParameters =
                    Parameters.build {
                        append("username", "first")
                        append("email", "first@example.com")
                        append("fullName", "First")
                        append("password", "StrongP@ss123")
                        append("confirmPassword", "StrongP@ss123")
                    },
            )

            // Second request should be rate limited
            val response =
                client.submitForm(
                    url = "/t/acme/register",
                    formParameters =
                        Parameters.build {
                            append("username", "second")
                            append("email", "second@example.com")
                            append("fullName", "Second")
                            append("password", "StrongP@ss123")
                            append("confirmPassword", "StrongP@ss123")
                        },
                )

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Base64Url(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
