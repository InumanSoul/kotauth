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
import com.kauth.domain.service.IdentifierCollisionCheck
import com.kauth.domain.service.MfaService
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 of v1.8.1 OIDC SSO: every successful interactive auth must drop a
 * signed `KOTAUTH_SSO` witness cookie scoped to `/t/{slug}`. Phase 3 will read
 * it; Phase 1 only writes it. These tests pin down the contract:
 *   - cookie is set on password login, magic-link consume, MFA challenge success
 *   - payload carries userId, tenantId, mfaCompleted, expiresAt
 *   - mfaCompleted reflects how the user authenticated (password=false,
 *     MFA=true, magic-link=false)
 */
class SsoCookieTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val authCodeRepo = FakeAuthorizationCodeRepository()
    private val sessionRepo = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val tokenPort = FakeTokenPort()
    private val emails = FakeEmailPort()
    private val evTokens = FakeEmailVerificationTokenRepository()
    private val prTokens = FakePasswordResetTokenRepository()
    private val passwordPolicy = FakePasswordPolicyPort()

    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val mfaService = mockk<MfaService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key-32-chars-minimum-len")

    private val tenant =
        Tenant(
            id = TenantId(7),
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
            id = UserId(42),
            tenantId = TenantId(7),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = hasher.hash("correct-pass"),
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
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
        )

    @BeforeTest
    fun reset() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        authCodeRepo.clear()
        sessionRepo.clear()
        prTokens.clear()
        evTokens.clear()
        emails.clear()
        auditLog.clear()
        tokenPort.reset()
        passwordPolicy.clear()
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
            collisionCheck = IdentifierCollisionCheck(userRepo),
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

    private fun selfService() =
        CredentialFlowService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = evTokens,
            prTokenRepo = prTokens,
            emailPort = emails,
            passwordPolicy = passwordPolicy,
            emailScope = CoroutineScope(Dispatchers.Unconfined),
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
                    credentialFlowService = selfService(),
                    mfaService = mfaService,
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                )
            }
        }

    private fun buildAuthContextCookie(): String {
        // Public client → PKCE challenge required by issueAuthorizationCode.
        // Use the RFC 7636 example pair (verifier omitted; the auth server only
        // checks the challenge at code-issuance time).
        val payload =
            listOf(
                "code",
                "spa-app",
                "https://app.example.com/callback",
                "openid",
                "",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "S256",
                "",
                System.currentTimeMillis().toString(),
            ).joinToString("|")
        return encryptionService.signCookie(payload)
    }

    /** Pulls the value of a Set-Cookie header by name; null if not present. URL-decodes the value. */
    private fun extractCookie(
        setCookie: List<String>,
        name: String,
    ): String? {
        val raw = setCookie.firstOrNull { it.startsWith("$name=") } ?: return null
        val encoded = raw.substringAfter("$name=").substringBefore(';')
        return java.net.URLDecoder.decode(encoded, Charsets.UTF_8)
    }

    private data class SsoPayload(
        val version: String,
        val userId: Int,
        val tenantId: Int,
        val authTimeEpochSec: Long,
        val mfaCompleted: Boolean,
        val expiresAtEpochSec: Long,
    )

    private fun parseSsoCookie(value: String): SsoPayload {
        val verified = encryptionService.verifyCookie(value)
        assertNotNull(verified, "SSO cookie must verify against the same signing key")
        val parts = verified.split("|")
        assertEquals(6, parts.size, "SSO cookie must have 6 pipe-delimited fields")
        return SsoPayload(
            version = parts[0],
            userId = parts[1].toInt(),
            tenantId = parts[2].toInt(),
            authTimeEpochSec = parts[3].toLong(),
            mfaCompleted = parts[4] == "1",
            expiresAtEpochSec = parts[5].toLong(),
        )
    }

    @Test
    fun `password login sets a signed KOTAUTH_SSO cookie with mfaCompleted=false`() =
        testApplication {
            every { mfaService.shouldChallengeMfa(any()) } returns false
            application(appBlock())

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/authorize",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "correct-pass")
                        },
                ) {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status, "Successful auth must redirect to client callback")
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            val ssoValue =
                extractCookie(setCookie, "KOTAUTH_SSO")
                    ?: error("KOTAUTH_SSO must be set on successful password login. Set-Cookie: $setCookie")

            val payload = parseSsoCookie(ssoValue)
            assertEquals("v1", payload.version)
            assertEquals(alice.id!!.value, payload.userId)
            assertEquals(tenant.id.value, payload.tenantId)
            assertEquals(false, payload.mfaCompleted, "Password-only login must record mfaCompleted=false")
            assertTrue(
                payload.expiresAtEpochSec > payload.authTimeEpochSec,
                "expiresAt must be in the future relative to authTime",
            )

            // Path scope must keep the cookie out of other tenants.
            val rawSetHeader = setCookie.first { it.startsWith("KOTAUTH_SSO=") }
            assertTrue(
                rawSetHeader.contains("Path=/t/acme") || rawSetHeader.contains("path=/t/acme"),
                "KOTAUTH_SSO must be path-scoped to /t/{slug}, was: $rawSetHeader",
            )
            assertTrue(
                rawSetHeader.contains("HttpOnly", ignoreCase = true),
                "KOTAUTH_SSO must be HttpOnly, was: $rawSetHeader",
            )
        }

    @Test
    fun `magic-link consume sets KOTAUTH_SSO cookie with mfaCompleted=false`() =
        testApplication {
            application(appBlock())

            // Issue the magic link, capture the token from the email
            selfService().initiateMagicLink(
                email = "alice@acme.com",
                tenantSlug = "acme",
                baseUrl = "http://localhost",
                ipAddress = null,
            )
            val rawToken =
                emails.sent
                    .single { it.type == "magic_link" }
                    .url
                    .substringAfter("?token=")
                    .substringBefore('&')

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("/t/acme/magic-link/consume?token=$rawToken") {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            val ssoValue =
                extractCookie(setCookie, "KOTAUTH_SSO")
                    ?: error("KOTAUTH_SSO must be set on magic-link consume success. Set-Cookie: $setCookie")

            val payload = parseSsoCookie(ssoValue)
            assertEquals(alice.id!!.value, payload.userId)
            assertEquals(tenant.id.value, payload.tenantId)
            assertEquals(false, payload.mfaCompleted, "Magic-link sign-in must record mfaCompleted=false")
        }

    @Test
    fun `MFA challenge success sets KOTAUTH_SSO cookie with mfaCompleted=true`() =
        testApplication {
            application(appBlock())

            // Bypass the MFA service — verifyTotp returns Success
            every { mfaService.verifyTotp(UserId(42), "123456") } returns
                com.kauth.domain.service.MfaResult
                    .Success(true)

            val mfaPending = "${alice.id!!.value}|acme|${System.currentTimeMillis()}"
            val mfaCookie = encryptionService.signCookie(mfaPending)

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/mfa-challenge",
                    formParameters =
                        Parameters.build {
                            append("code", "123456")
                        },
                ) {
                    header(
                        "Cookie",
                        listOf(
                            "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}",
                            "KOTAUTH_MFA_PENDING=$mfaCookie",
                        ).joinToString("; "),
                    )
                }

            assertEquals(HttpStatusCode.Found, response.status, "MFA success must redirect to client callback")
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            val ssoValue =
                extractCookie(setCookie, "KOTAUTH_SSO")
                    ?: error("KOTAUTH_SSO must be set on MFA challenge success. Set-Cookie: $setCookie")

            val payload = parseSsoCookie(ssoValue)
            assertEquals(alice.id.value, payload.userId)
            assertEquals(true, payload.mfaCompleted, "MFA-completed login must record mfaCompleted=true")
        }

    @Test
    fun `an MFA pending cookie minted for another tenant issues no session here`() =
        testApplication {
            application(appBlock())

            every { mfaService.verifyTotp(UserId(42), "123456") } returns
                com.kauth.domain.service.MfaResult
                    .Success(true)

            // Signed by us, unexpired, and answered with a code the holder really owns — only the
            // slug says it was minted somewhere else, and nothing compared it until now.
            val mfaCookie = encryptionService.signCookie("${alice.id!!.value}|otherco|${System.currentTimeMillis()}")

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/mfa-challenge",
                    formParameters =
                        Parameters.build {
                            append("code", "123456")
                        },
                ) {
                    header(
                        "Cookie",
                        listOf(
                            "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}",
                            "KOTAUTH_MFA_PENDING=$mfaCookie",
                        ).joinToString("; "),
                    )
                }

            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            assertEquals(
                null,
                extractCookie(setCookie, "KOTAUTH_SSO"),
                "A pending challenge from another tenant must leave no session: $setCookie",
            )
            assertEquals("/t/acme/authorize", response.headers["Location"])
        }

    @Test
    fun `KOTAUTH_SSO cookie is NOT set on failed password login`() =
        testApplication {
            every { mfaService.shouldChallengeMfa(any()) } returns false
            application(appBlock())

            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.submitForm(
                    url = "/t/acme/authorize",
                    formParameters =
                        Parameters.build {
                            append("username", "alice")
                            append("password", "wrong-pass")
                        },
                ) {
                    header("Cookie", "KOTAUTH_AUTH_CONTEXT=${buildAuthContextCookie()}")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val setCookie = response.headers.getAll("Set-Cookie") ?: emptyList()
            assertTrue(
                setCookie.none { it.startsWith("KOTAUTH_SSO=") && !it.startsWith("KOTAUTH_SSO=;") },
                "KOTAUTH_SSO must NOT be set on a failed login. Set-Cookie: $setCookie",
            )
        }
}
