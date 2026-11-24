package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.IdentifierCollisionCheck
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GET /authorize must refuse an unknown or disabled client, or a redirect_uri the client never
 * registered, before it renders the login page (RFC 6749 §3.1.2.4, §4.1.2.1): a 400 error page,
 * no redirect, and no auth-context cookie that would carry the untrusted URI into the login flow.
 */
class AuthorizeTargetRefusalTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private fun app(
        clientId: String,
        accessType: AccessType = AccessType.PUBLIC,
        enabled: Boolean = true,
        redirectUris: List<String> = listOf("https://app.example.com/callback"),
    ) = Application(
        id = ApplicationId(0),
        tenantId = TenantId(1),
        clientId = clientId,
        name = clientId,
        description = null,
        accessType = accessType,
        enabled = enabled,
        redirectUris = redirectUris,
        grantTypes = GrantType.defaultsFor(accessType),
    )

    private fun ApplicationTestBuilder.installApp() {
        tenantRepo.add(
            Tenant(
                id = TenantId(1),
                slug = "acme",
                displayName = "Acme",
                issuerUrl = null,
                theme = TenantTheme.DEFAULT,
            ),
        )
        appRepo.add(app("spa-app"))
        appRepo.add(app("retired-app", enabled = false))
        appRepo.add(app("cli-app", redirectUris = listOf("http://127.0.0.1:8400/callback")))
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService =
                        AuthService(
                            userRepository = userRepo,
                            tenantRepository = tenantRepo,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            identifierResolver = UserIdentifierResolver(userRepo),
                            collisionCheck = IdentifierCollisionCheck(userRepo),
                        ),
                    oauthService =
                        OAuthService(
                            tenantRepository = tenantRepo,
                            userRepository = userRepo,
                            applicationRepository = appRepo,
                            sessionRepository = sessionRepo,
                            authCodeRepository = FakeAuthorizationCodeRepository(),
                            tokenPort = tokenPort,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                        ),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService = mockk<CredentialFlowService>(relaxed = true),
                    mfaService = mockk<MfaService>(relaxed = true),
                    encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len"),
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.authorize(
        clientId: String,
        redirectUri: String,
        prompt: String? = null,
    ): HttpResponse {
        val noFollow = createClient { followRedirects = false }
        return noFollow.get(
            "/t/acme/authorize?response_type=code&scope=openid&state=s1" +
                "&client_id=${clientId.encodeURLParameter()}" +
                "&redirect_uri=${redirectUri.encodeURLParameter()}" +
                (prompt?.let { "&prompt=$it" } ?: ""),
        )
    }

    private suspend fun assertRefusedWithoutLogin(
        response: HttpResponse,
        expectedDetail: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(response.headers[HttpHeaders.Location], "must not redirect anywhere")
        val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertFalse(cookies.any { it.startsWith("KOTAUTH_AUTH_CONTEXT=") }, "no auth context may be set: $cookies")
        val body = response.bodyAsText()
        assertTrue(body.contains("Sign-in stopped"), "must render the refusal page")
        assertFalse(body.contains("type=\"password\""), "must not render a password field")
        assertTrue(body.contains(expectedDetail), "page names the cause for the developer")
    }

    @Test
    fun `a redirect_uri the client never registered is refused before the login page`() =
        testApplication {
            installApp()
            assertRefusedWithoutLogin(
                authorize("spa-app", "https://evil.example/cb"),
                "redirect_uri is not registered for this client",
            )
        }

    @Test
    fun `an unknown client_id is refused before the login page`() =
        testApplication {
            installApp()
            assertRefusedWithoutLogin(authorize("no-such-app", "https://app.example.com/callback"), "unknown client_id")
        }

    @Test
    fun `a disabled client is refused before the login page`() =
        testApplication {
            installApp()
            assertRefusedWithoutLogin(
                authorize("retired-app", "https://app.example.com/callback"),
                "client is disabled",
            )
        }

    @Test
    fun `a registered redirect_uri still renders the login page with an auth context`() =
        testApplication {
            installApp()
            val response = authorize("spa-app", "https://app.example.com/callback")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("type=\"password\""))
            val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            assertTrue(cookies.any { it.startsWith("KOTAUTH_AUTH_CONTEXT=") })
        }

    @Test
    fun `a public client's loopback redirect_uri on another port still renders the login page`() =
        testApplication {
            installApp()
            val response = authorize("cli-app", "http://127.0.0.1:53111/callback")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `prompt=none with an unregistered redirect_uri still answers a 400 without redirecting`() =
        testApplication {
            installApp()
            val response = authorize("spa-app", "https://evil.example/cb", prompt = "none")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertNull(response.headers[HttpHeaders.Location])
        }
}
