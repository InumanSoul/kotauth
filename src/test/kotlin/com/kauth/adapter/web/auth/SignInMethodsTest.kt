package com.kauth.adapter.web.auth

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuthMethods
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sign-in's methods reach the authorization code as RFC 8176 `amr`, and `acr_values` asking
 * for multi-factor makes the sign-in owe a second factor, from scratch or on top of an SSO session.
 */
class SignInMethodsTest {
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
            securityConfig = SecurityConfig(mfaPolicy = "optional"),
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
                            passwordHasher = hasher,
                            auditLog = auditLog,
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

    private val authorizeUrl =
        "/t/acme/authorize?response_type=code&client_id=spa-app" +
            "&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback&scope=openid" +
            "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"
    private val multiFactor = "&acr_values=" + java.net.URLEncoder.encode(AuthMethods.ACR_MULTI_FACTOR, "UTF-8")

    private fun ssoCookie(
        amr: List<String>,
        authTime: Instant = Instant.now().minusSeconds(60),
        version: String = "v2",
    ): String {
        val flag = if (version == "v1") (if ("mfa" in amr) "1" else "0") else amr.joinToString(",")
        val expires = Instant.now().plusSeconds(3600).epochSecond
        return encryptionService.signCookie("$version|10|1|${authTime.epochSecond}|$flag|$expires")
    }

    private fun enrollAlice(): String {
        val secret = TotpUtil.generateSecret()
        mfaRepository.saveEnrollment(
            MfaEnrollment(userId = alice.id!!, tenantId = acme.id, secret = secret, verified = true),
        )
        return secret
    }

    private fun issuedAmr() = authCodes.all().single().amr

    private suspend fun Browser.signInWithPassword() =
        post("/t/acme/authorize", mapOf("username" to "alice", "password" to "correct-horse"))

    @Test
    fun `a password sign-in is pwd`() =
        testApplication {
            setUp()
            val response = browser(enroll = null).signInWithPassword()

            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            assertEquals(listOf("pwd"), issuedAmr())
        }

    @Test
    fun `a password and an authenticator code are pwd, otp and mfa`() =
        testApplication {
            setUp()
            val secret = enrollAlice()
            val browser = browser(enroll = null)
            assertEquals("/t/acme/mfa-challenge", browser.signInWithPassword().headers["Location"])

            val response = browser.post("/t/acme/mfa-challenge", mapOf("code" to TotpUtil.generateCode(secret)))

            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            assertEquals(listOf("pwd", "otp", "mfa"), issuedAmr())
        }

    @Test
    fun `asking for multi-factor sends a user without a second factor to set one up`() =
        testApplication {
            setUp()
            val browser = browser(enroll = null)
            browser.cookies.remove("KOTAUTH_AUTH_CONTEXT")
            assertEquals(HttpStatusCode.OK, browser.get(authorizeUrl + multiFactor).status)

            val response = browser.signInWithPassword()

            assertEquals("/t/acme/mfa-enroll", response.headers["Location"])
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `the same sign-in without acr_values owes nothing in an optional workspace`() =
        testApplication {
            setUp()
            val browser = browser(enroll = null)
            browser.cookies.remove("KOTAUTH_AUTH_CONTEXT")
            browser.get(authorizeUrl)

            val response = browser.signInWithPassword()

            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
        }

    @Test
    fun `asking for multi-factor on a password session asks only for the second factor`() =
        testApplication {
            setUp()
            val secret = enrollAlice()
            val browser = browser(enroll = null)
            browser.cookies["KOTAUTH_SSO"] = ssoCookie(listOf("pwd"))

            val stepUp = browser.get(authorizeUrl + multiFactor)
            assertEquals("/t/acme/mfa-challenge", stepUp.headers["Location"], "No password page for a step-up")

            val response = browser.post("/t/acme/mfa-challenge", mapOf("code" to TotpUtil.generateCode(secret)))
            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            assertEquals(listOf("pwd", "otp", "mfa"), issuedAmr())
        }

    @Test
    fun `a step-up that needs interaction answers login_required to prompt=none`() =
        testApplication {
            setUp()
            enrollAlice()
            val browser = browser(enroll = null)
            browser.cookies["KOTAUTH_SSO"] = ssoCookie(listOf("pwd"))

            val response = browser.get("$authorizeUrl$multiFactor&prompt=none")

            assertTrue(response.headers["Location"].orEmpty().contains("error=login_required"))
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `a multi-factor session satisfies acr_values silently and keeps its methods and time`() =
        testApplication {
            setUp()
            enrollAlice()
            val authTime = Instant.now().minusSeconds(120)
            val browser = browser(enroll = null)
            browser.cookies["KOTAUTH_SSO"] = ssoCookie(listOf("pwd", "otp", "mfa"), authTime)

            val response = browser.get(authorizeUrl + multiFactor)

            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            val code = authCodes.all().single()
            assertEquals(listOf("pwd", "otp", "mfa"), code.amr)
            assertEquals(authTime.epochSecond, code.authTime?.epochSecond)
        }

    @Test
    fun `an enrolled user with a single-factor session is challenged before a silent code`() =
        testApplication {
            setUp()
            enrollAlice()
            val browser = browser(enroll = null)
            browser.cookies["KOTAUTH_SSO"] = ssoCookie(listOf("pwd"))

            val response = browser.get(authorizeUrl)

            assertEquals("/t/acme/mfa-challenge", response.headers["Location"])
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `a cookie from before amr was kept still signs in silently`() =
        testApplication {
            setUp()
            val browser = browser(enroll = null)
            browser.cookies["KOTAUTH_SSO"] = ssoCookie(listOf("mfa"), version = "v1")

            val response = browser.get(authorizeUrl)

            assertTrue(response.headers["Location"].orEmpty().startsWith("https://app.example.com/callback?code="))
            assertEquals(listOf("mfa"), issuedAmr())
        }

    @Test
    fun `discovery advertises amr, acr and the multi-factor acr value`() =
        testApplication {
            setUp()
            val body = browser(enroll = null).get("/t/acme/.well-known/openid-configuration").bodyAsText()

            assertTrue(body.contains("\"amr\"") && body.contains("\"acr\""))
            assertTrue(body.contains("\"acr_values_supported\":[\"${AuthMethods.ACR_MULTI_FACTOR}\"]"))
        }
}
