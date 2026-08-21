package com.kauth.adapter.web.portal

import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Role
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.WebAuthnService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [portalRoutes] — session guard and OAuth flow edges.
 *
 * Focus: unauthenticated access redirects + logout + callback error paths.
 * The full OAuth PKCE exchange (EncryptionService.signCookie / verifyCookie)
 * is tested at the domain/infra layer.
 * Here we verify the route wiring behaves correctly at the boundary.
 */
class PortalRoutesTest {
    private val encryptionService = EncryptionService("test-secret-key")
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val sessionRepo = FakeSessionRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

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
            email = "alice@acme.dev",
            fullName = "Alice",
            passwordHash = hasher.hash("secret"),
            enabled = true,
        )

    private fun buildSelfService() =
        AccountSelfService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            emailPort = FakeEmailPort(),
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        auditLogPort.clear()
        tenantRepo.add(tenant)
        userRepo.add(user)
    }

    // =========================================================================
    // Session guard — unauthenticated access
    // =========================================================================

    @Test
    fun `GET profile redirects to login when no session cookie is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/profile")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("/t/acme/account/login"), "Must redirect to portal login")
        }

    @Test
    fun `GET security redirects to login when no session cookie is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/security")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/t/acme/account/login") == true)
        }

    @Test
    fun `POST change-password redirects to login when no session cookie is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/account/change-password",
                    formParameters =
                        Parameters.build {
                            append("current_password", "secret")
                            append("new_password", "new-secret")
                            append("confirm_password", "new-secret")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/t/acme/account/login") == true)
        }

    // =========================================================================
    // GET /login (fallback — no oauthService wired)
    // =========================================================================

    @Test
    fun `GET login returns 200 with login form when oauthService is null`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/acme/account/login")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("login") || body.contains("Login") || body.contains("form"))
        }

    // =========================================================================
    // GET /callback — error paths
    // =========================================================================

    @Test
    fun `GET callback redirects to login when no code and no oauthService`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/callback")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("/t/acme/account/login"), "Must redirect to login on missing code")
        }

    // =========================================================================
    // POST /logout
    // =========================================================================

    @Test
    fun `POST logout redirects to login`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/account/logout",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/t/acme/account/login") == true)
        }

    @Test
    fun `POST logout clears KOTAUTH_SSO cookie`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/account/logout",
                    formParameters = Parameters.build { },
                )

            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            val ssoClear = setCookie.firstOrNull { it.startsWith("KOTAUTH_SSO=") }
            assertTrue(
                ssoClear != null &&
                    (
                        ssoClear.contains("Max-Age=0", ignoreCase = true) ||
                            ssoClear.startsWith("KOTAUTH_SSO=;")
                    ),
                "Portal logout must wipe KOTAUTH_SSO so silent SSO doesn't bring the user back. Was: $setCookie",
            )
        }

    // =========================================================================
    // Phase 5 — portal silent SSO via prompt=none
    // =========================================================================

    @Test
    fun `GET login redirects to authorize with prompt=none on first attempt`() =
        testApplication {
            application { installTestAppWithOAuth() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/login")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("prompt=none"),
                "First /login attempt must include prompt=none, was: $location",
            )
        }

    @Test
    fun `GET login with prompt_failed=true OMITS prompt=none to break the loop`() =
        testApplication {
            application { installTestAppWithOAuth() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/login?prompt_failed=true")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                !location.contains("prompt=none"),
                "Loop-break attempt must NOT include prompt=none, was: $location",
            )
        }

    @Test
    fun `GET callback with error=login_required redirects to login with prompt_failed=true`() =
        testApplication {
            application { installTestAppWithOAuth() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/callback?error=login_required")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(
                location.contains("/t/acme/account/login") && location.contains("prompt_failed=true"),
                "login_required failure must loop back to /login?prompt_failed=true, was: $location",
            )
        }

    // =========================================================================
    // Test app wiring
    // =========================================================================

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL")
        }
        routing {
            portalRoutes(
                accountSelfService = buildSelfService(),
                tenantRepository = tenantRepo,
                encryptionService = encryptionService,
                translationPort = EnglishOnlyTranslation(),
            )
        }
    }

    // =========================================================================
    // POST-magic-link passkey enrollment landing (/account/enroll-passkey)
    // =========================================================================

    @Test
    fun `GET enroll-passkey redirects to login when no portal session is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/account/enroll-passkey")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"]?.contains("/t/acme/account/login") == true,
                "Unauthenticated enroll-passkey must redirect to login",
            )
        }

    @Test
    fun `GET enroll-passkey returns 200 with enrollment page when portal session is present`() =
        testApplication {
            application { installTestAppWithSession() }

            val noFollow = createClient { followRedirects = false }

            // Establish a session via the helper route, then carry the cookie forward
            val loginResponse = noFollow.get("/t/acme/account/test-set-session")
            val sessionCookie =
                loginResponse.headers
                    .getAll("Set-Cookie")
                    ?.firstOrNull { it.startsWith("KOTAUTH_PORTAL=") }
                    ?.substringBefore(";")
                    ?: error("Session cookie not set")

            val response =
                client.get("/t/acme/account/enroll-passkey") {
                    header("Cookie", sessionCookie)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("Sign in complete") || body.contains("passkey"),
                "Page must render enrollment content",
            )
        }

    @Test
    fun `portal callback redirects to enroll-passkey for passwordLoginDisabled tenant`() =
        testApplication {
            val passwordlessRepo = FakeTenantRepository()
            val passwordlessTenant =
                tenant.copy(
                    securityConfig = tenant.securityConfig.copy(passwordLoginEnabled = false),
                )
            passwordlessRepo.add(passwordlessTenant)
            userRepo.add(user)

            val authCodes = FakeAuthorizationCodeRepository()
            val jwtTokenPort = JwtFakeTokenPort(userId = user.id!!.value, username = user.username)
            val apps = FakeApplicationRepository()
            apps.add(
                Application(
                    id = ApplicationId(1),
                    tenantId = TenantId(1),
                    clientId = "kotauth-portal",
                    name = "Portal",
                    description = null,
                    accessType = com.kauth.domain.model.AccessType.PUBLIC,
                    enabled = true,
                    redirectUris = listOf("http://localhost/t/acme/account/callback"),
                    grantTypes = GrantType.defaultsFor(com.kauth.domain.model.AccessType.PUBLIC),
                ),
            )

            val oauthService =
                OAuthService(
                    tenantRepository = passwordlessRepo,
                    userRepository = userRepo,
                    applicationRepository = apps,
                    sessionRepository = sessionRepo,
                    authCodeRepository = authCodes,
                    tokenPort = jwtTokenPort,
                    passwordHasher = hasher,
                    auditLog = auditLogPort,
                )

            // Pre-seed a valid auth code for the callback to exchange
            val rawCode = "test-code-passwordless"
            authCodes.save(
                AuthorizationCode(
                    code = rawCode,
                    tenantId = TenantId(1),
                    clientId = ApplicationId(1),
                    userId = user.id!!,
                    redirectUri = "http://localhost/t/acme/account/callback",
                    scopes = "openid profile email",
                    expiresAt = Instant.now().plusSeconds(300),
                ),
            )

            application {
                install(ContentNegotiation) { json() }
                install(Sessions) { cookie<PortalSession>("KOTAUTH_PORTAL") }
                routing {
                    portalRoutes(
                        accountSelfService =
                            AccountSelfService(
                                userRepository = userRepo,
                                tenantRepository = passwordlessRepo,
                                sessionRepository = sessionRepo,
                                passwordHasher = hasher,
                                auditLog = auditLogPort,
                                emailPort = FakeEmailPort(),
                                emailScope = CoroutineScope(Dispatchers.Unconfined),
                            ),
                        tenantRepository = passwordlessRepo,
                        encryptionService = encryptionService,
                        oauthService = oauthService,
                        baseUrl = "http://localhost",
                        translationPort = EnglishOnlyTranslation(),
                    )
                }
            }

            // Build a PKCE cookie so the callback accepts the request
            val pkcePayload =
                encryptionService.signCookie(
                    "verifier|acme|${System.currentTimeMillis()}|state123",
                )
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/account/callback?code=$rawCode&state=state123") {
                    header("Cookie", "KOTAUTH_PORTAL_PKCE=$pkcePayload")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                "/t/acme/account/enroll-passkey",
                response.headers["Location"],
                "passwordLoginDisabled callback must redirect to enroll-passkey",
            )
        }

    @Test
    fun `callback with passwordLoginDisabled=true skips enroll-passkey redirect when user has passkeys`() =
        testApplication {
            val passwordlessRepo = FakeTenantRepository()
            val passwordlessTenant =
                tenant.copy(
                    securityConfig = tenant.securityConfig.copy(passwordLoginEnabled = false),
                )
            passwordlessRepo.add(passwordlessTenant)
            userRepo.add(user)

            val credRepo = FakeWebAuthnCredentialRepository()
            credRepo.save(
                WebAuthnCredential(
                    userId = user.id!!,
                    tenantId = TenantId(1),
                    credentialId = "existing-passkey",
                    publicKeyCose = ByteArray(77),
                    signCounter = 0L,
                    aaguid = null,
                    transports = listOf("internal"),
                    name = "My Device",
                    backupEligible = false,
                    backupState = false,
                    createdAt = Instant.now(),
                    lastUsedAt = null,
                ),
            )
            val webAuthnSvc =
                WebAuthnService(
                    credentialRepository = credRepo,
                    relyingParty = FakeRelyingPartyAdapter(),
                    secretKey = "test-secret-key-32-chars-minimum!!",
                    auditLog = auditLogPort,
                    userRepository = userRepo,
                    tenantRepository = passwordlessRepo,
                )

            val authCodes = FakeAuthorizationCodeRepository()
            val jwtTokenPort = JwtFakeTokenPort(userId = user.id!!.value, username = user.username)
            val apps = FakeApplicationRepository()
            apps.add(
                Application(
                    id = ApplicationId(1),
                    tenantId = TenantId(1),
                    clientId = "kotauth-portal",
                    name = "Portal",
                    description = null,
                    accessType = com.kauth.domain.model.AccessType.PUBLIC,
                    enabled = true,
                    redirectUris = listOf("http://localhost/t/acme/account/callback"),
                    grantTypes = GrantType.defaultsFor(com.kauth.domain.model.AccessType.PUBLIC),
                ),
            )

            val oauthService =
                OAuthService(
                    tenantRepository = passwordlessRepo,
                    userRepository = userRepo,
                    applicationRepository = apps,
                    sessionRepository = sessionRepo,
                    authCodeRepository = authCodes,
                    tokenPort = jwtTokenPort,
                    passwordHasher = hasher,
                    auditLog = auditLogPort,
                )

            val rawCode = "test-code-has-passkeys"
            authCodes.save(
                AuthorizationCode(
                    code = rawCode,
                    tenantId = TenantId(1),
                    clientId = ApplicationId(1),
                    userId = user.id!!,
                    redirectUri = "http://localhost/t/acme/account/callback",
                    scopes = "openid profile email",
                    expiresAt = Instant.now().plusSeconds(300),
                ),
            )

            application {
                install(ContentNegotiation) { json() }
                install(Sessions) { cookie<PortalSession>("KOTAUTH_PORTAL") }
                routing {
                    portalRoutes(
                        accountSelfService =
                            AccountSelfService(
                                userRepository = userRepo,
                                tenantRepository = passwordlessRepo,
                                sessionRepository = sessionRepo,
                                passwordHasher = hasher,
                                auditLog = auditLogPort,
                                emailPort = FakeEmailPort(),
                                emailScope = CoroutineScope(Dispatchers.Unconfined),
                            ),
                        tenantRepository = passwordlessRepo,
                        encryptionService = encryptionService,
                        oauthService = oauthService,
                        webAuthnService = webAuthnSvc,
                        baseUrl = "http://localhost",
                        translationPort = EnglishOnlyTranslation(),
                    )
                }
            }

            val pkcePayload =
                encryptionService.signCookie(
                    "verifier|acme|${System.currentTimeMillis()}|state789",
                )
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/account/callback?code=$rawCode&state=state789") {
                    header("Cookie", "KOTAUTH_PORTAL_PKCE=$pkcePayload")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                "/t/acme/launcher",
                response.headers["Location"],
                "User with passkeys on passwordless tenant must skip enroll-passkey and go to launcher",
            )
        }

    @Test
    fun `portal callback redirects to launcher for tenant with password login enabled`() =
        testApplication {
            val authCodes = FakeAuthorizationCodeRepository()
            val jwtTokenPort = JwtFakeTokenPort(userId = user.id!!.value, username = user.username)
            val apps = FakeApplicationRepository()
            apps.add(
                Application(
                    id = ApplicationId(1),
                    tenantId = TenantId(1),
                    clientId = "kotauth-portal",
                    name = "Portal",
                    description = null,
                    accessType = com.kauth.domain.model.AccessType.PUBLIC,
                    enabled = true,
                    redirectUris = listOf("http://localhost/t/acme/account/callback"),
                    grantTypes = GrantType.defaultsFor(com.kauth.domain.model.AccessType.PUBLIC),
                ),
            )

            val oauthService =
                OAuthService(
                    tenantRepository = tenantRepo,
                    userRepository = userRepo,
                    applicationRepository = apps,
                    sessionRepository = sessionRepo,
                    authCodeRepository = authCodes,
                    tokenPort = jwtTokenPort,
                    passwordHasher = hasher,
                    auditLog = auditLogPort,
                )

            val rawCode = "test-code-normal"
            authCodes.save(
                AuthorizationCode(
                    code = rawCode,
                    tenantId = TenantId(1),
                    clientId = ApplicationId(1),
                    userId = user.id!!,
                    redirectUri = "http://localhost/t/acme/account/callback",
                    scopes = "openid profile email",
                    expiresAt = Instant.now().plusSeconds(300),
                ),
            )

            application {
                install(ContentNegotiation) { json() }
                install(Sessions) { cookie<PortalSession>("KOTAUTH_PORTAL") }
                routing {
                    portalRoutes(
                        accountSelfService = buildSelfService(),
                        tenantRepository = tenantRepo,
                        encryptionService = encryptionService,
                        oauthService = oauthService,
                        baseUrl = "http://localhost",
                        translationPort = EnglishOnlyTranslation(),
                    )
                }
            }

            val pkcePayload =
                encryptionService.signCookie(
                    "verifier|acme|${System.currentTimeMillis()}|state456",
                )
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/account/callback?code=$rawCode&state=state456") {
                    header("Cookie", "KOTAUTH_PORTAL_PKCE=$pkcePayload")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                "/t/acme/launcher",
                response.headers["Location"],
                "Normal tenant callback must redirect to launcher",
            )
        }

    private fun io.ktor.server.application.Application.installTestAppWithSession() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            get("/t/{slug}/account/test-set-session") {
                val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.sessions.set(
                    PortalSession(
                        userId = 10,
                        tenantId = 1,
                        tenantSlug = slug,
                        username = "alice",
                    ),
                )
                call.respond(HttpStatusCode.OK, "ok")
            }
            portalRoutes(
                accountSelfService = buildSelfService(),
                tenantRepository = tenantRepo,
                encryptionService = encryptionService,
                translationPort = EnglishOnlyTranslation(),
            )
        }
    }

    /**
     * Wires a real (fake-backed) OAuthService — required for the prompt=none
     * silent-SSO branch in /login since that branch only fires when oauthService
     * is non-null.
     */
    private fun io.ktor.server.application.Application.installTestAppWithOAuth() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL")
        }
        val oauthService =
            OAuthService(
                tenantRepository = tenantRepo,
                userRepository = userRepo,
                applicationRepository = FakeApplicationRepository(),
                sessionRepository = sessionRepo,
                authCodeRepository = FakeAuthorizationCodeRepository(),
                tokenPort = FakeTokenPort(),
                passwordHasher = hasher,
                auditLog = auditLogPort,
            )
        routing {
            portalRoutes(
                accountSelfService = buildSelfService(),
                tenantRepository = tenantRepo,
                encryptionService = encryptionService,
                oauthService = oauthService,
                baseUrl = "http://localhost",
                translationPort = EnglishOnlyTranslation(),
            )
        }
    }
}

/**
 * TokenPort that emits a structurally valid JWT so `decodeJwtPayload` in the
 * portal callback can extract `sub` and `preferred_username`.
 */
private class JwtFakeTokenPort(
    private val userId: Int,
    private val username: String,
) : com.kauth.domain.port.TokenPort {
    override fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String?,
        roles: List<Role>,
        customAccessClaims: Map<String, String>,
        customIdClaims: Map<String, String>,
        authTime: java.time.Instant?,
        actingSubject: UserId?,
        audiences: List<String>,
    ): TokenResponse {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"sub":$userId,"preferred_username":"$username"}""".toByteArray(),
            )
        val fakeJwt = "$header.$payload.sig"
        return TokenResponse(
            access_token = fakeJwt,
            token_type = "Bearer",
            expires_in = 3600,
            refresh_token = "fake-refresh",
            refresh_expires_in = 86400,
            id_token = if ("openid" in scopes) fakeJwt else null,
            scope = scopes.joinToString(" "),
        )
    }

    override fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>,
        audiences: List<String>,
    ): String = "fake-m2m"

    override fun decodeAccessToken(
        token: String,
        expectedIssuer: String,
    ): AccessTokenClaims? = null

    override fun issuerFor(tenant: Tenant): String = "https://fake-issuer/${tenant.slug}"

    override fun getTenantJwks(tenantId: com.kauth.domain.model.TenantId): List<Map<String, Any>> = emptyList()

    override fun invalidateSigningKeyCache(tenantId: com.kauth.domain.model.TenantId) {}
}
