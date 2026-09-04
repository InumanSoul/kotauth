package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserIdentifierResolver
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordPolicyPort
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP integration tests for [magicLinkRoutes].
 *
 * Regression coverage for the v1.7.1 token-burn race fix: a cross-device tap
 * (no `KOTAUTH_AUTH_CONTEXT` cookie) must NOT consume the token, so a
 * same-device retry continues to work.
 */
class MagicLinkRoutesTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val emails = FakeEmailPort()
    private val tokenPort = FakeTokenPort()
    private val evTokens = FakeEmailVerificationTokenRepository()
    private val prTokens = FakePasswordResetTokenRepository()
    private val passwordPolicy = FakePasswordPolicyPort()

    private val encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len")

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            smtpUsername = "noreply@acme.com",
            smtpPassword = "secret",
            smtpFromAddress = "noreply@acme.com",
            smtpFromName = "Acme",
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
            emailVerified = false,
        )

    /** A second workspace on the same instance, with its own magic-link user. */
    private val globex =
        Tenant(
            id = TenantId(2),
            slug = "globex",
            displayName = "Globex",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            smtpUsername = "noreply@globex.com",
            smtpPassword = "secret",
            smtpFromAddress = "noreply@globex.com",
            smtpFromName = "Globex",
            securityConfig = SecurityConfig(magicLinkEnabled = true),
        )

    private val bob =
        User(
            id = UserId(20),
            tenantId = TenantId(2),
            username = "bob",
            email = "bob@globex.com",
            fullName = "Bob",
            passwordHash = hasher.hash("doesnt-matter"),
            enabled = true,
            emailVerified = false,
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

    private val globexApp =
        Application(
            id = ApplicationId(2),
            tenantId = TenantId(2),
            clientId = "globex-app",
            name = "Globex SPA",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://globex.example.com/callback"),
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
        )

    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private fun credentialFlowService() =
        CredentialFlowService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = evTokens,
            prTokenRepo = prTokens,
            emailPort = emails,
            passwordPolicy = passwordPolicy,
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun authService() =
        AuthService(
            userRepository = users,
            tenantRepository = tenants,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessions,
            identifierResolver = UserIdentifierResolver(users),
        )

    private fun oauthService() =
        OAuthService(
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            sessionRepository = sessions,
            authCodeRepository = authCodes,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    private fun buildAuthContextCookie(
        clientId: String = "spa-app",
        redirectUri: String = "https://app.example.com/callback",
    ): String {
        // Public clients require PKCE — use a deterministic challenge so the
        // test exercises a realistic /authorize-style cookie payload.
        val payload =
            listOf(
                "code",
                clientId,
                redirectUri,
                "openid",
                "",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "S256",
                "",
                System.currentTimeMillis().toString(),
            ).joinToString("|")
        return encryptionService.signCookie(payload)
    }

    @BeforeTest
    fun reset() {
        tenants.clear()
        users.clear()
        apps.clear()
        authCodes.clear()
        sessions.clear()
        prTokens.clear()
        evTokens.clear()
        emails.clear()
        auditLog.clear()
        tokenPort.reset()
        passwordPolicy.clear()

        tenants.add(tenant)
        users.add(alice)
        apps.add(spaApp)
    }

    private fun extractToken(url: String): String = url.substringAfter("?token=").substringBefore("&")

    private fun appBlock(): io.ktor.server.application.Application.() -> Unit =
        {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService = authService(),
                    oauthService = oauthService(),
                    tenantRepository = tenants,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService = credentialFlowService(),
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }

    @Test
    fun `a magic-link token from another workspace issues no code at this one`() =
        testApplication {
            tenants.add(globex)
            users.add(bob)
            application(appBlock())

            // A token minted at globex is presented at acme's URL. The consume guard refuses the
            // pair; nothing downstream of it runs.
            credentialFlowService().initiateMagicLink(
                email = "bob@globex.com",
                tenantSlug = "globex",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken = extractToken(emails.sent.single { it.type == "magic_link" }.url)

            val response =
                createClient { followRedirects = false }
                    .get("/t/acme/magic-link/consume?token=$rawToken") {
                        header("Cookie", "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}")
                    }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(
                authCodes.all().isEmpty(),
                "No authorization code may be written for a user of another workspace",
            )
            assertTrue(
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .none { it.contains("KOTAUTH_SSO=") },
                "No SSO witness may be written either: ${response.headers.getAll("Set-Cookie")}",
            )
        }

    @Test
    fun `a cross-workspace consume leaves the token unconsumed and usable at its own workspace`() =
        testApplication {
            tenants.add(globex)
            users.add(bob)
            apps.add(globexApp)
            application(appBlock())

            credentialFlowService().initiateMagicLink(
                email = "bob@globex.com",
                tenantSlug = "globex",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken = extractToken(emails.sent.single { it.type == "magic_link" }.url)
            val noFollow = createClient { followRedirects = false }

            // 1. The link is tapped at the wrong workspace — refused before any state change.
            val crossWorkspace =
                noFollow.get("/t/acme/magic-link/consume?token=$rawToken") {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}")
                }
            assertEquals(HttpStatusCode.Unauthorized, crossWorkspace.status)

            val token = prTokens.all().single { it.purpose == TokenPurpose.MAGIC_LINK }
            assertTrue(token.isValid, "A consume aimed at another workspace must not burn the token")
            assertEquals(null, token.usedAt, "Token must not be marked used by another workspace's URL")

            // 2. The owner taps the same link at their own workspace — it still works.
            val ownWorkspace =
                noFollow.get("/t/globex/magic-link/consume?token=$rawToken") {
                    header(
                        "Cookie",
                        "KOTAUTH_AUTH_CONTEXT=" +
                            buildAuthContextCookie("globex-app", "https://globex.example.com/callback"),
                    )
                }
            assertEquals(
                HttpStatusCode.Found,
                ownWorkspace.status,
                "The owner's retry must succeed because the cross-workspace tap did not burn the token",
            )
            val location = ownWorkspace.headers["Location"]
            assertNotNull(location)
            assertTrue(
                location.startsWith("https://globex.example.com/callback?code="),
                "Owner consume must redirect to their own client callback, was: $location",
            )
        }

    // =========================================================================
    // Regression — cross-device tap must NOT consume the token
    // =========================================================================

    @Test
    fun `consume without auth-context cookie preserves the token for same-device retry`() =
        testApplication {
            application(appBlock())

            // Issue the magic link
            credentialFlowService().initiateMagicLink(
                email = "alice@acme.com",
                tenantSlug = "acme",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken = extractToken(emails.sent.single { it.type == "magic_link" }.url)

            // Cross-device tap: NO KOTAUTH_AUTH_CONTEXT cookie
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/magic-link/consume?token=$rawToken")

            assertEquals(HttpStatusCode.Unauthorized, response.status)

            // The token MUST still be alive — not marked used, not deleted
            val token = prTokens.all().single { it.purpose == TokenPurpose.MAGIC_LINK }
            assertTrue(token.isValid, "Token must remain valid after a cross-device tap")
            assertEquals(null, token.usedAt, "Token must not be marked used")
        }

    @Test
    fun `consume with auth-context cookie completes OAuth flow and burns the token`() =
        testApplication {
            application(appBlock())

            credentialFlowService().initiateMagicLink(
                email = "alice@acme.com",
                tenantSlug = "acme",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken = extractToken(emails.sent.single { it.type == "magic_link" }.url)
            val cookie = buildAuthContextCookie()

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/magic-link/consume?token=$rawToken") {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=$cookie")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(
                location.startsWith("https://app.example.com/callback?code="),
                "Same-device consume must redirect to the OAuth client callback with a code, was: $location",
            )

            val token = prTokens.all().single { it.purpose == TokenPurpose.MAGIC_LINK }
            assertEquals(true, token.isUsed, "Token must be consumed on successful same-device sign-in")
        }

    @Test
    fun `cross-device tap then same-device retry succeeds on the same token`() =
        testApplication {
            application(appBlock())

            credentialFlowService().initiateMagicLink(
                email = "alice@acme.com",
                tenantSlug = "acme",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken = extractToken(emails.sent.single { it.type == "magic_link" }.url)

            val noFollow = createClient { followRedirects = false }

            // 1. User taps the link on a different device — no cookie
            val crossDeviceResponse = noFollow.get("/t/acme/magic-link/consume?token=$rawToken")
            assertEquals(HttpStatusCode.Unauthorized, crossDeviceResponse.status)

            // 2. User goes back to the original device, clicks again — cookie present
            val cookie = buildAuthContextCookie()
            val sameDeviceResponse =
                noFollow.get("/t/acme/magic-link/consume?token=$rawToken") {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=$cookie")
                }
            assertEquals(
                HttpStatusCode.Found,
                sameDeviceResponse.status,
                "Same-device retry must succeed because the cross-device tap did not burn the token",
            )
        }

    // =========================================================================
    // Sanity — feature toggle off
    // =========================================================================

    @Test
    fun `consume returns redirect to login when magic-link disabled on tenant`() =
        testApplication {
            tenants.clear()
            tenants.add(tenant.copy(securityConfig = SecurityConfig(magicLinkEnabled = false)))
            application(appBlock())

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/magic-link/consume?token=anything")
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/account/login") == true)
        }

    // =========================================================================
    // POST /magic-link/send — always redirects to ?sent=true (enumeration safe)
    // =========================================================================

    @Test
    fun `send always redirects to sent=true regardless of email`() =
        testApplication {
            application(appBlock())

            val noFollow = createClient { followRedirects = false }

            val knownResponse =
                noFollow.submitForm(
                    url = "/t/acme/magic-link/send",
                    formParameters = Parameters.build { append("email", "alice@acme.com") },
                )
            val unknownResponse =
                noFollow.submitForm(
                    url = "/t/acme/magic-link/send",
                    formParameters = Parameters.build { append("email", "nobody@nowhere.com") },
                )

            assertEquals(HttpStatusCode.Found, knownResponse.status)
            assertEquals(HttpStatusCode.Found, unknownResponse.status)
            assertTrue(knownResponse.headers["Location"]?.contains("?sent=true") == true)
            assertTrue(unknownResponse.headers["Location"]?.contains("?sent=true") == true)
        }
}
