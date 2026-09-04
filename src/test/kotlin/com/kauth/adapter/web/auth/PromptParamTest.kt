package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserIdentifierResolver
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 of v1.8.1 OIDC SSO: GET /authorize must honor the OIDC `prompt`
 * parameter (Core §3.1.2.1). Phase 3 will read the SSO cookie for silent
 * auth — for now, `prompt=none` always returns `login_required`, and
 * `prompt=login` / `prompt=select_account` clear the SSO cookie before
 * re-rendering the login page.
 */
class PromptParamTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodeRepo = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val mfaService = mockk<MfaService>(relaxed = true)
    private val selfService = mockk<CredentialFlowService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(magicLinkEnabled = true),
        )

    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("doesnt-matter"),
            enabled = true,
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

    @BeforeTest
    fun reset() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        authCodeRepo.clear()
        sessionRepo.clear()
        auditLog.clear()
        tokenPort.reset()
        tenantRepo.add(tenant)
        userRepo.add(alice)
        appRepo.add(spaApp)
    }

    private fun authService() =
        AuthService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessionRepo,
            identifierResolver = UserIdentifierResolver(userRepo),
        )

    private fun oauthService() =
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

    private fun appBlock(): io.ktor.server.application.Application.() -> Unit =
        {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService = authService(),
                    oauthService = oauthService(),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService = selfService,
                    mfaService = mfaService,
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }

    private val authorizeUrl =
        "/t/acme/authorize" +
            "?response_type=code" +
            "&client_id=spa-app" +
            "&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback" +
            "&scope=openid"

    @Test
    fun `prompt=none with valid client redirects to redirect_uri with login_required`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&state=xyz&prompt=none")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(
                location.startsWith("https://app.example.com/callback?"),
                "Must redirect to the registered redirect_uri, was: $location",
            )
            assertTrue(location.contains("error=login_required"), "Must include error=login_required")
            assertTrue(location.contains("state=xyz"), "Must echo the state param back")
        }

    @Test
    fun `prompt=none with hostile redirect_uri rejects with 400 instead of redirecting`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            // Attacker-supplied redirect_uri not in client's registered list
            val hostile =
                "/t/acme/authorize" +
                    "?response_type=code" +
                    "&client_id=spa-app" +
                    "&redirect_uri=https%3A%2F%2Fattacker.example.com%2Fphish" +
                    "&prompt=none"
            val response = noFollow.get(hostile)

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "Open-redirect must be refused — never bounce a login_required to an unregistered uri",
            )
            assertEquals(null, response.headers["Location"])
        }

    @Test
    fun `prompt=login renders login page and clears any existing SSO cookie`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&prompt=login")

            assertEquals(HttpStatusCode.OK, response.status, "prompt=login renders the login form")
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            // Clearing a cookie sets it to empty with Max-Age=0
            val ssoClear =
                setCookie.firstOrNull { it.startsWith("KOTAUTH_SSO=") }
                    ?: error("KOTAUTH_SSO clear directive must be present, was: $setCookie")
            assertTrue(
                ssoClear.contains("Max-Age=0", ignoreCase = true) || ssoClear.startsWith("KOTAUTH_SSO=;"),
                "KOTAUTH_SSO clear must zero Max-Age, was: $ssoClear",
            )
        }

    @Test
    fun `prompt=select_account also clears the SSO cookie`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&prompt=select_account")

            assertEquals(HttpStatusCode.OK, response.status)
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            assertTrue(
                setCookie.any { it.startsWith("KOTAUTH_SSO=") && it.contains("Max-Age=0", ignoreCase = true) },
                "select_account must also wipe the SSO witness cookie, was: $setCookie",
            )
        }

    @Test
    fun `unknown prompt value is rejected with invalid_request`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&prompt=please_log_in")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `prompt=none cannot be combined with other prompt values`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&prompt=none%20login")

            assertEquals(HttpStatusCode.BadRequest, response.status, "OIDC §3.1.2.1: none is exclusive")
        }

    @Test
    fun `discovery doc advertises prompt_values_supported`() =
        testApplication {
            application(appBlock())
            val response = client.get("/t/acme/.well-known/openid-configuration")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"prompt_values_supported\""),
                "discovery doc must advertise prompt_values_supported",
            )
            assertTrue(body.contains("\"none\""), "Must include 'none' in prompt_values_supported")
            assertTrue(body.contains("\"login\""), "Must include 'login' in prompt_values_supported")
        }
}
