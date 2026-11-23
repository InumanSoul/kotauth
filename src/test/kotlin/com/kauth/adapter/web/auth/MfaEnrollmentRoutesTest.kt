package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.IdentifierCollisionCheck
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.UserIdentifierResolver
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeMfaRepository
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
import com.kauth.infrastructure.TotpUtil
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Second-factor setup inside the sign-in: a workspace that requires MFA walks a user who has
 * none through setting up an authenticator app, then finishes the sign-in.
 */
class MfaEnrollmentRoutesTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()
    private val mfaRepository = FakeMfaRepository()
    private val encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len")
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)

    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            securityConfig = SecurityConfig(mfaPolicy = "required"),
        )

    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-horse"),
            enabled = true,
            emailVerified = true,
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

    private fun mfaService() = MfaService(mfaRepository, users, tenants, hasher, auditLog)

    @BeforeTest
    fun reset() {
        tenants.clear()
        users.clear()
        apps.clear()
        authCodes.clear()
        sessions.clear()
        auditLog.clear()
        tokenPort.reset()
        mfaRepository.clear()
        tenants.add(acme)
        users.add(alice)
        apps.add(spaApp)
    }

    private fun ApplicationTestBuilder.setUp(mfaRateLimiter: InMemoryRateLimiter = limiter) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService =
                        AuthService(
                            userRepository = users,
                            tenantRepository = tenants,
                            tokenPort = tokenPort,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            sessionRepository = sessions,
                            identifierResolver = UserIdentifierResolver(users),
                            collisionCheck = IdentifierCollisionCheck(users),
                        ),
                    oauthService =
                        OAuthService(
                            tenantRepository = tenants,
                            userRepository = users,
                            applicationRepository = apps,
                            sessionRepository = sessions,
                            authCodeRepository = authCodes,
                            tokenPort = tokenPort,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                        ),
                    tenantRepository = tenants,
                    credentialFlowService =
                        CredentialFlowService(
                            userRepository = users,
                            tenantRepository = tenants,
                            sessionRepository = sessions,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            evTokenRepo = FakeEmailVerificationTokenRepository(),
                            prTokenRepo = FakePasswordResetTokenRepository(),
                            emailPort = FakeEmailPort(),
                            passwordPolicy = FakePasswordPolicyPort(),
                            emailScope = CoroutineScope(Dispatchers.Unconfined),
                        ),
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                    mfaService = mfaService(),
                    mfaRateLimiter = mfaRateLimiter,
                )
            }
        }
    }

    private fun authContextCookie(): String =
        encryptionService.signCookie(
            listOf(
                "code",
                "spa-app",
                "https://app.example.com/callback",
                "openid",
                "xyz",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "S256",
                "",
                System.currentTimeMillis().toString(),
            ).joinToString("|"),
        )

    private fun enrollCookie(
        enrollmentId: Int = 0,
        verified: Boolean = false,
        slug: String = "acme",
        startedAt: Long = System.currentTimeMillis(),
    ): String = encryptionService.signCookie("10|$slug|$startedAt|$enrollmentId|${if (verified) 1 else 0}|pwd")

    /** A browser's cookie jar, enough for this flow: the response's cookies replace the request's. */
    private class Browser(
        val client: HttpClient,
        val cookies: MutableMap<String, String>,
    ) {
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

    private fun ApplicationTestBuilder.browser(enroll: String? = enrollCookie()): Browser {
        val cookies = mutableMapOf("KOTAUTH_AUTH_CONTEXT" to authContextCookie())
        if (enroll != null) cookies[MFA_ENROLL_COOKIE] = enroll
        return Browser(createClient { followRedirects = false }, cookies)
    }

    private fun currentCode(): String =
        TotpUtil.generateCode(assertNotNull(mfaRepository.findEnrollmentByUserId(alice.id!!)).secret)

    @Test
    fun `a password sign-in without the required second factor goes to the setup`() =
        testApplication {
            setUp()
            val browser = browser(enroll = null)

            val response =
                browser.post(
                    "/t/acme/authorize",
                    mapOf("username" to "alice", "password" to "correct-horse"),
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("/t/acme/mfa-enroll", response.headers["Location"])
            assertTrue(MFA_ENROLL_COOKIE in browser.cookies)
            assertTrue(authCodes.all().isEmpty(), "No code before the second factor exists")
        }

    @Test
    fun `the whole setup ends in an authorization code and shows the recovery codes once`() =
        testApplication {
            setUp()
            val browser = browser()

            val intro = browser.get("/t/acme/mfa-enroll")
            assertEquals(HttpStatusCode.OK, intro.status)
            assertTrue(intro.bodyAsText().contains("/t/acme/mfa-enroll/start"))

            val setup = browser.post("/t/acme/mfa-enroll/start")
            assertEquals(HttpStatusCode.OK, setup.status)
            assertTrue(setup.bodyAsText().contains("data-totp-uri=\"otpauth://totp/"))
            assertTrue(mfaRepository.findUnusedRecoveryCodes(alice.id!!).isEmpty(), "Codes come at the end")

            val done = browser.post("/t/acme/mfa-enroll/verify", mapOf("code" to currentCode()))
            assertEquals(HttpStatusCode.OK, done.status)
            val unused = mfaRepository.findUnusedRecoveryCodes(alice.id!!)
            assertEquals(MfaService.RECOVERY_CODE_COUNT, unused.size)
            val shown =
                Regex(
                    "<li><code>([0-9a-f]{16})</code></li>",
                ).findAll(done.bodyAsText()).map { it.groupValues[1] }
            assertEquals(MfaService.RECOVERY_CODE_COUNT, shown.count())
            assertTrue(shown.all { code -> unused.any { hasher.verify(code, it.codeHash) } })
            assertTrue(assertNotNull(mfaRepository.findEnrollmentByUserId(alice.id!!)).verified)

            val reload = browser.get("/t/acme/mfa-enroll")
            assertFalse(Regex("<li><code>").containsMatchIn(reload.bodyAsText()), "Recovery codes are shown once")

            val finish = browser.post("/t/acme/mfa-enroll/complete")
            assertEquals(HttpStatusCode.Found, finish.status)
            assertTrue(finish.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            assertEquals(1, authCodes.all().size)
            assertFalse(MFA_ENROLL_COOKIE in browser.cookies, "The setup cookie is spent")
        }

    @Test
    fun `a wrong code keeps the same QR code and does not enroll`() =
        testApplication {
            setUp()
            val browser = browser()
            val first = browser.post("/t/acme/mfa-enroll/start").bodyAsText()
            val uri = Regex("data-totp-uri=\"([^\"]+)\"").find(first)!!.groupValues[1]

            val retry = browser.post("/t/acme/mfa-enroll/verify", mapOf("code" to "000000"))

            assertEquals(HttpStatusCode.Unauthorized, retry.status)
            assertTrue(retry.bodyAsText().contains(uri), "The user keeps scanning the same secret")
            assertFalse(assertNotNull(mfaRepository.findEnrollmentByUserId(alice.id!!)).verified)
        }

    @Test
    fun `completing before the new factor is verified issues nothing`() =
        testApplication {
            setUp()
            val browser = browser()
            browser.post("/t/acme/mfa-enroll/start")

            val response = browser.post("/t/acme/mfa-enroll/complete")

            assertEquals("/t/acme/mfa-enroll", response.headers["Location"])
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `a verified cookie is not enough without a verified enrollment`() =
        testApplication {
            setUp()
            mfaRepository.saveEnrollment(MfaEnrollment(userId = alice.id!!, tenantId = acme.id, secret = "secret"))
            val browser = browser(enroll = enrollCookie(enrollmentId = 1, verified = true))

            val response = browser.post("/t/acme/mfa-enroll/complete")

            assertEquals("/t/acme/mfa-enroll", response.headers["Location"])
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `a code only verifies the enrollment this sign-in started`() =
        testApplication {
            setUp()
            val browser = browser()
            browser.post("/t/acme/mfa-enroll/start")
            // Something else replaces the enrollment, e.g. a second tab starting over.
            mfaRepository.deleteEnrollmentsByUser(alice.id!!)
            mfaRepository.saveEnrollment(
                MfaEnrollment(userId = alice.id!!, tenantId = acme.id, secret = TotpUtil.generateSecret()),
            )

            val response = browser.post("/t/acme/mfa-enroll/verify", mapOf("code" to currentCode()))

            assertEquals("/t/acme/mfa-enroll", response.headers["Location"])
            assertFalse(assertNotNull(mfaRepository.findEnrollmentByUserId(alice.id!!)).verified)
        }

    @Test
    fun `without a valid setup cookie every step goes back to sign-in`() =
        testApplication {
            setUp()
            val cases =
                mapOf(
                    "missing" to null,
                    "another workspace" to enrollCookie(slug = "globex"),
                    "expired" to enrollCookie(startedAt = System.currentTimeMillis() - 16 * 60 * 1000),
                    "tampered" to enrollCookie().dropLast(2) + "xx",
                )
            for ((case, cookie) in cases) {
                val browser = browser(enroll = cookie)
                for (step in listOf("start", "verify", "complete")) {
                    val response = browser.post("/t/acme/mfa-enroll/$step", mapOf("code" to "123456"))
                    assertEquals("/t/acme/account/login", response.headers["Location"], "$case / $step")
                }
            }
            assertEquals(null, mfaRepository.findEnrollmentByUserId(alice.id!!))
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `the setup needs the OAuth request it belongs to`() =
        testApplication {
            setUp()
            val browser = browser()
            browser.cookies.remove("KOTAUTH_AUTH_CONTEXT")

            val response = browser.post("/t/acme/mfa-enroll/start")

            assertEquals("/t/acme/account/login", response.headers["Location"])
            assertEquals(null, mfaRepository.findEnrollmentByUserId(alice.id!!))
        }

    @Test
    fun `verification attempts are rate limited`() =
        testApplication {
            setUp(mfaRateLimiter = InMemoryRateLimiter(maxRequests = 2, windowSeconds = 60))
            val browser = browser()
            browser.post("/t/acme/mfa-enroll/start")
            repeat(2) { browser.post("/t/acme/mfa-enroll/verify", mapOf("code" to "000000")) }

            val response = browser.post("/t/acme/mfa-enroll/verify", mapOf("code" to currentCode()))

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertFalse(assertNotNull(mfaRepository.findEnrollmentByUserId(alice.id!!)).verified)
        }
}
