package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
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
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 of v1.8.1 OIDC SSO: every logout path must wipe the KOTAUTH_SSO
 * witness. Otherwise the next /authorize would silent-auth the user back
 * into the session they just signed out of.
 */
class SsoLogoutTest {
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
            id = TenantId(7),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(magicLinkEnabled = true),
        )

    private val alice =
        User(
            id = UserId(42),
            tenantId = TenantId(7),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("doesnt-matter"),
            enabled = true,
            emailVerified = true,
        )

    private val spaApp =
        Application(
            id = ApplicationId(1),
            tenantId = TenantId(7),
            clientId = "spa-app",
            name = "SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://app.example.com/callback"),
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

    private fun assertSsoCookieCleared(setCookie: List<String>) {
        val ssoClear = setCookie.firstOrNull { it.startsWith("KOTAUTH_SSO=") }
        assertTrue(ssoClear != null, "KOTAUTH_SSO clear directive must be present, was: $setCookie")
        assertTrue(
            ssoClear.contains("Max-Age=0", ignoreCase = true) || ssoClear.startsWith("KOTAUTH_SSO=;"),
            "KOTAUTH_SSO cookie must be cleared with Max-Age=0, was: $ssoClear",
        )
    }

    @Test
    fun `GET end_session clears KOTAUTH_SSO`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/protocol/openid-connect/logout")

            assertEquals(HttpStatusCode.Found, response.status)
            assertSsoCookieCleared(response.headers.getAll("Set-Cookie") ?: emptyList())
        }

    @Test
    fun `POST end_session clears KOTAUTH_SSO`() =
        testApplication {
            application(appBlock())
            val response =
                client.submitForm(
                    url = "/t/acme/protocol/openid-connect/logout",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertSsoCookieCleared(response.headers.getAll("Set-Cookie") ?: emptyList())
        }

    @Test
    fun `GET end_session rejects protocol-relative double-slash redirect`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=//evil.com/x")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertFalse(location.contains("evil.com"), "Must not redirect to evil.com, got: $location")
        }

    @Test
    fun `GET end_session rejects protocol-relative backslash redirect`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=%2F%5Cevil.com/x")

            assertEquals(HttpStatusCode.Found, response.status)
            assertFalse((response.headers["Location"] ?: "").contains("evil.com"))
        }

    @Test
    fun `GET end_session rejects double-backslash redirect`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=%5C%5Cevil.com/x")

            assertEquals(HttpStatusCode.Found, response.status)
            assertFalse((response.headers["Location"] ?: "").contains("evil.com"))
        }

    @Test
    fun `GET end_session rejects external absolute URI`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=https://evil.com/x")

            assertEquals(HttpStatusCode.Found, response.status)
            assertFalse((response.headers["Location"] ?: "").contains("evil.com"))
        }

    @Test
    fun `GET end_session rejects URL-encoded protocol-relative redirect`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=%2F%2Fevil.com/x")

            assertEquals(HttpStatusCode.Found, response.status)
            assertFalse((response.headers["Location"] ?: "").contains("evil.com"))
        }

    @Test
    fun `GET end_session accepts safe relative path`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/protocol/openid-connect/logout?post_logout_redirect_uri=/dashboard")

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/dashboard", response.headers["Location"])
        }
}
