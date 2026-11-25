package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.ConsentService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.IdentifierCollisionCheck
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserIdentifierResolver
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeConsentRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentFlowTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val consents = FakeConsentRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len")

    private val tenant =
        Tenant(
            id = TenantId(7),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(mfaPolicy = "optional"),
        )
    private val alice =
        User(
            id = UserId(42),
            tenantId = TenantId(7),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-horse"),
            enabled = true,
            emailVerified = true,
        )
    private val partner =
        Application(
            id = ApplicationId(1),
            tenantId = TenantId(7),
            clientId = "partner-app",
            name = "Partner Reports",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://partner.example.com/callback"),
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
            consentRequired = true,
        )

    private val consentService =
        ConsentService(consents, appRepo, sessionRepo, userRepo, auditLog)

    @BeforeTest
    fun reset() {
        listOf(tenantRepo::clear, userRepo::clear, appRepo::clear, authCodes::clear, sessionRepo::clear).forEach {
            it()
        }
        consents.clear()
        auditLog.clear()
        tenantRepo.add(tenant)
        userRepo.add(alice)
        appRepo.add(partner)
    }

    private fun ApplicationTestBuilder.setUp() {
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
                            authCodeRepository = authCodes,
                            tokenPort = FakeTokenPort(),
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            consentService = consentService,
                        ),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService = mockk<CredentialFlowService>(relaxed = true),
                    mfaService = mockk<MfaService>(relaxed = true),
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                    consentService = consentService,
                )
            }
        }
    }

    private fun authorizeUrl(extra: String = "") =
        "/t/acme/authorize?response_type=code&client_id=partner-app" +
            "&redirect_uri=https%3A%2F%2Fpartner.example.com%2Fcallback&scope=openid%20email&state=s1" +
            "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256$extra"

    private class Browser(
        val client: HttpClient,
    ) {
        val cookies = mutableMapOf<String, String>()

        private fun header() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        private fun remember(response: HttpResponse) {
            response.headers.getAll("Set-Cookie").orEmpty().forEach { raw ->
                val (name, value) = raw.substringBefore(";").split("=", limit = 2)
                if (value.isEmpty() || raw.contains("Max-Age=0")) cookies.remove(name) else cookies[name] = value
            }
        }

        suspend fun get(path: String): HttpResponse = client.get(path) { header("Cookie", header()) }.also(::remember)

        suspend fun post(
            path: String,
            form: Map<String, String> = emptyMap(),
        ): HttpResponse =
            client
                .submitForm(path, Parameters.build { form.forEach { (k, v) -> append(k, v) } }) {
                    header("Cookie", header())
                }.also(::remember)
    }

    private fun ApplicationTestBuilder.browser() = Browser(createClient { followRedirects = false })

    private suspend fun Browser.signIn(extra: String = ""): HttpResponse {
        get(authorizeUrl(extra))
        return post("/t/acme/authorize", mapOf("username" to "alice", "password" to "correct-horse"))
    }

    private fun nonceIn(page: String): String =
        assertNotNull(Regex("name=\"nonce\" value=\"([^\"]+)\"").find(page)).groupValues[1]

    @Test
    fun `a sign-in to an app that asks goes to the consent screen, and Allow issues the code`() =
        testApplication {
            setUp()
            val browser = browser()

            val signedIn = browser.signIn()
            assertEquals("/t/acme/consent", signedIn.headers["Location"])
            assertTrue(authCodes.all().isEmpty(), "No code before the user allows it")

            val page = browser.get("/t/acme/consent").bodyAsText()
            assertContains(page, "Partner Reports wants to access your account")
            assertContains(page, "Signed in as alice@acme.com")
            assertContains(page, "Confirm who you are")
            assertContains(page, "See your email address")

            val allowed = browser.post("/t/acme/consent", mapOf("nonce" to nonceIn(page), "decision" to "allow"))

            val location = allowed.headers["Location"].orEmpty()
            assertTrue(location.startsWith("https://partner.example.com/callback?code="), location)
            assertContains(location, "state=s1")
            assertEquals(setOf("openid", "email"), consents.find(tenant.id, alice.id!!, partner.id)?.scopes)
            assertTrue(auditLog.hasEvent(AuditEventType.CONSENT_GRANTED))
        }

    @Test
    fun `once allowed, the next sign-in goes straight back to the app`() =
        testApplication {
            setUp()
            consents.save(tenant.id, alice.id!!, partner.id, setOf("openid", "email"), Instant.now())

            val signedIn = browser().signIn()

            assertTrue(signedIn.headers["Location"].orEmpty().startsWith("https://partner.example.com/callback?code="))
        }

    @Test
    fun `Deny sends access_denied back to the app and issues nothing`() =
        testApplication {
            setUp()
            val browser = browser()
            browser.signIn()
            val page = browser.get("/t/acme/consent").bodyAsText()

            val denied = browser.post("/t/acme/consent", mapOf("nonce" to nonceIn(page), "decision" to "deny"))

            val location = denied.headers["Location"].orEmpty()
            assertTrue(location.startsWith("https://partner.example.com/callback?error=access_denied"), location)
            assertContains(location, "state=s1")
            assertTrue(authCodes.all().isEmpty())
            assertNull(consents.find(tenant.id, alice.id!!, partner.id))
            assertTrue(auditLog.hasEvent(AuditEventType.CONSENT_DENIED))
        }

    @Test
    fun `a post without the page's nonce changes nothing`() =
        testApplication {
            setUp()
            val browser = browser()
            browser.signIn()

            val forged = browser.post("/t/acme/consent", mapOf("nonce" to "guess", "decision" to "allow"))

            assertEquals(HttpStatusCode.BadRequest, forged.status)
            assertNull(consents.find(tenant.id, alice.id!!, partner.id))
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `the consent page without a pending sign-in says the request expired`() =
        testApplication {
            setUp()

            val response = browser().get("/t/acme/consent")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "This request has expired")
        }

    @Test
    fun `an SSO session still owes consent, and prompt=none gets consent_required`() =
        testApplication {
            setUp()
            val browser = browser()
            val page = browser.signIn()
            assertEquals("/t/acme/consent", page.headers["Location"])
            assertTrue("KOTAUTH_SSO" in browser.cookies, "The sign-in itself succeeded")

            val silent = browser.get(authorizeUrl("&prompt=none"))
            assertTrue(
                silent.headers["Location"].orEmpty().startsWith(
                    "https://partner.example.com/callback?error=consent_required",
                ),
            )

            val interactive = browser.get(authorizeUrl())
            assertEquals("/t/acme/consent", interactive.headers["Location"], "SSO skips the password, not consent")
        }

    @Test
    fun `prompt=consent asks again without asking for the password again`() =
        testApplication {
            setUp()
            consents.save(tenant.id, alice.id!!, partner.id, setOf("openid", "email"), Instant.now())
            val browser = browser()
            browser.signIn()

            val again = browser.get(authorizeUrl("&prompt=consent"))

            assertEquals("/t/acme/consent", again.headers["Location"])
            val page = browser.get("/t/acme/consent").bodyAsText()
            val allowed = browser.post("/t/acme/consent", mapOf("nonce" to nonceIn(page), "decision" to "allow"))
            assertTrue(allowed.headers["Location"].orEmpty().startsWith("https://partner.example.com/callback?code="))
        }

    @Test
    fun `an app with the switch off never shows the screen`() =
        testApplication {
            setUp()
            appRepo.add(partner.copy(consentRequired = false))

            val signedIn = browser().signIn()

            assertTrue(signedIn.headers["Location"].orEmpty().startsWith("https://partner.example.com/callback?code="))
            assertFalse(auditLog.hasEvent(AuditEventType.CONSENT_GRANTED))
        }
}
