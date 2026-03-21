package com.kauth.adapter.web.portal

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [portalRoutes] — session guard and OAuth flow edges.
 *
 * Focus: unauthenticated access redirects + logout + callback error paths.
 * The full OAuth PKCE exchange (EncryptionService.signCookie / verifyCookie)
 * depends on KAUTH_SECRET_KEY and is tested at the domain/infra layer.
 * Here we verify the route wiring behaves correctly at the boundary.
 */
class PortalRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val sessionRepo = FakeSessionRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val tokenPort = FakeTokenPort()

    private val tenant = Tenant(
        id = 1,
        slug = "acme",
        displayName = "Acme Corp",
        issuerUrl = null,
        theme = TenantTheme.DEFAULT,
    )

    private val user = User(
        id = 10,
        tenantId = 1,
        username = "alice",
        email = "alice@acme.dev",
        fullName = "Alice",
        passwordHash = hasher.hash("secret"),
        enabled = true,
    )

    private fun buildAuthService() = AuthService(
        userRepository = userRepo,
        tenantRepository = tenantRepo,
        tokenPort = tokenPort,
        passwordHasher = hasher,
        auditLog = auditLogPort,
        sessionRepository = sessionRepo,
    )

    private fun buildSelfService() = UserSelfServiceService(
        userRepository = userRepo,
        tenantRepository = tenantRepo,
        sessionRepository = sessionRepo,
        passwordHasher = hasher,
        auditLog = auditLogPort,
        evTokenRepo = FakeEmailVerificationTokenRepository(),
        prTokenRepo = FakePasswordResetTokenRepository(),
        emailPort = FakeEmailPort(),
    )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(tenant)
        userRepo.add(user)
    }

    // =========================================================================
    // Session guard — unauthenticated access
    // =========================================================================

    @Test
    fun `GET profile redirects to login when no session cookie is present`() = testApplication {
        application { installTestApp() }

        val noFollow = createClient { followRedirects = false }
        val response = noFollow.get("/t/acme/account/profile")

        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers["Location"] ?: ""
        assertTrue(location.contains("/t/acme/account/login"), "Must redirect to portal login")
    }

    @Test
    fun `GET security redirects to login when no session cookie is present`() = testApplication {
        application { installTestApp() }

        val noFollow = createClient { followRedirects = false }
        val response = noFollow.get("/t/acme/account/security")

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("/t/acme/account/login") == true)
    }

    @Test
    fun `POST change-password redirects to login when no session cookie is present`() = testApplication {
        application { installTestApp() }

        val noFollow = createClient { followRedirects = false }
        val response = noFollow.submitForm(
            url = "/t/acme/account/change-password",
            formParameters = Parameters.build {
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
    fun `GET login returns 200 with login form when oauthService is null`() = testApplication {
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
    fun `GET callback redirects to login when no code and no oauthService`() = testApplication {
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
    fun `POST logout redirects to login`() = testApplication {
        application { installTestApp() }

        val noFollow = createClient { followRedirects = false }
        val response = noFollow.submitForm(
            url = "/t/acme/account/logout",
            formParameters = Parameters.build { },
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("/t/acme/account/login") == true)
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
                authService = buildAuthService(),
                selfServiceService = buildSelfService(),
                tenantRepository = tenantRepo,
            )
        }
    }
}
