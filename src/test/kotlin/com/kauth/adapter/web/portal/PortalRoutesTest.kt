package com.kauth.adapter.web.portal

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AccountSelfService
import com.kauth.domain.service.OAuthService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
