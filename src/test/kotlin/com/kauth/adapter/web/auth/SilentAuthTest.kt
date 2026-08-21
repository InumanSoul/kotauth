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
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3 of v1.8.1 OIDC SSO: GET /authorize with a valid `KOTAUTH_SSO`
 * cookie issues an authorization code and bounces straight back to the RP
 * — no UI shown. Honors `prompt=none`, `max_age`, `id_token_hint` per
 * OIDC Core §3.1.2.
 */
class SilentAuthTest {
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
            securityConfig = SecurityConfig(magicLinkEnabled = true, mfaPolicy = "optional"),
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

    /**
     * Pre-mints a signed SSO cookie that mirrors what `setSsoCookie` would
     * produce after a successful interactive login.
     */
    private fun ssoCookie(
        userId: Int = alice.id!!.value,
        tenantId: Int = tenant.id.value,
        authTime: Instant = Instant.now(),
        mfaCompleted: Boolean = false,
        ttlSeconds: Long = 86_400,
    ): String {
        val expires = Instant.now().plusSeconds(ttlSeconds)
        val payload =
            listOf(
                "v1",
                userId.toString(),
                tenantId.toString(),
                authTime.epochSecond.toString(),
                if (mfaCompleted) "1" else "0",
                expires.epochSecond.toString(),
            ).joinToString("|")
        return encryptionService.signCookie(payload)
    }

    private val authorizeUrl =
        "/t/acme/authorize" +
            "?response_type=code" +
            "&client_id=spa-app" +
            "&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback" +
            "&scope=openid" +
            "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" +
            "&code_challenge_method=S256"

    @Test
    fun `valid SSO cookie issues code without rendering UI`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&state=xyz") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status, "Silent auth must redirect, not render")
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(
                location.startsWith("https://app.example.com/callback?code="),
                "Must redirect to client callback with code, was: $location",
            )
            assertTrue(location.contains("state=xyz"), "State must echo back")
        }

    @Test
    fun `prompt=none with valid SSO cookie returns code instead of login_required`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&prompt=none&state=xyz") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            assertTrue(
                location.contains("code=") && !location.contains("error="),
                "prompt=none with cookie must succeed, was: $location",
            )
        }

    @Test
    fun `prompt=none without SSO cookie returns login_required`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("$authorizeUrl&prompt=none&state=xyz")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            assertTrue(location.contains("error=login_required"))
        }

    @Test
    fun `prompt=login skips silent auth even with valid cookie`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&prompt=login") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie()}")
                }

            assertEquals(HttpStatusCode.OK, response.status, "prompt=login must render the login form, not silent-auth")
        }

    @Test
    fun `max_age=0 forces re-auth even with fresh SSO cookie`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            // max_age=0 means "the user must re-prove credentials right now"
            val response =
                noFollow.get("$authorizeUrl&prompt=none&max_age=0") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie(authTime = Instant.now())}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains("error=login_required"))
        }

    @Test
    fun `max_age satisfied when authTime is within the window`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&prompt=none&max_age=600") {
                    header(
                        "Cookie",
                        "KOTAUTH_SSO=${ssoCookie(authTime = Instant.now().minusSeconds(60))}",
                    )
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            assertTrue(location.contains("code="), "60s ≤ 600s max_age, must succeed: $location")
        }

    @Test
    fun `max_age violated when authTime is older than the window`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&prompt=none&max_age=300") {
                    header(
                        "Cookie",
                        "KOTAUTH_SSO=${ssoCookie(authTime = Instant.now().minusSeconds(900))}",
                    )
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains("error=login_required"))
        }

    @Test
    fun `id_token_hint with matching sub allows silent auth`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            // Manufacture a token whose payload's "sub" matches the cookie userId.
            // Signature is not verified (best-effort hint).
            val payload =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"42","iss":"https://idp.example.com"}""".toByteArray())
            val fakeIdToken = "eyJhbGciOiJSUzI1NiJ9.$payload.SIGNATURE_NOT_CHECKED"

            val response =
                noFollow.get("$authorizeUrl&prompt=none&id_token_hint=$fakeIdToken") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains("code="))
        }

    @Test
    fun `id_token_hint with mismatched sub blocks silent auth`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            // Hint claims sub=999 but cookie says userId=42 → not the same session.
            val payload =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"sub":"999","iss":"https://idp.example.com"}""".toByteArray())
            val fakeIdToken = "eyJhbGciOiJSUzI1NiJ9.$payload.SIGNATURE_NOT_CHECKED"

            val response =
                noFollow.get("$authorizeUrl&prompt=none&id_token_hint=$fakeIdToken") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie()}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"]!!.contains("error=login_required"),
                "id_token_hint sub mismatch must reject silent auth",
            )
        }

    @Test
    fun `tenant requires MFA but cookie says mfaCompleted=false blocks silent auth`() =
        testApplication {
            tenantRepo.clear()
            tenantRepo.add(tenant.copy(securityConfig = tenant.securityConfig.copy(mfaPolicy = "required")))
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val response =
                noFollow.get("$authorizeUrl&prompt=none") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie(mfaCompleted = false)}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"]!!.contains("error=login_required"),
                "Tenant policy change to required must invalidate non-MFA cookies for silent auth",
            )
        }

    @Test
    fun `cross-tenant cookie cannot silent-auth`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            // Cookie says tenantId=99 but the request is for tenant 7 (acme)
            val response =
                noFollow.get("$authorizeUrl&prompt=none") {
                    header("Cookie", "KOTAUTH_SSO=${ssoCookie(tenantId = 99)}")
                }

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]!!.contains("error=login_required"))
        }

    @Test
    fun `silent auth records cookie's authTime onto the issued AuthorizationCode`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val pinnedAuthTime = Instant.parse("2026-04-29T10:00:00Z")

            noFollow.get(authorizeUrl) {
                header("Cookie", "KOTAUTH_SSO=${ssoCookie(authTime = pinnedAuthTime)}")
            }

            val codes = authCodeRepo.all()
            assertEquals(1, codes.size, "Silent auth should have minted exactly one code")
            assertEquals(
                pinnedAuthTime.epochSecond,
                codes.single().authTime?.epochSecond,
                "Issued code must carry the SSO cookie's authTime, not Instant.now()",
            )
        }

    @Test
    fun `auth_time from cookie surfaces on the issued ID token via TokenPort`() =
        testApplication {
            application(appBlock())
            val noFollow = createClient { followRedirects = false }
            val pinnedAuthTime = Instant.parse("2026-04-29T11:00:00Z")

            // Silent auth → issues a code carrying authTime
            noFollow.get(authorizeUrl) {
                header("Cookie", "KOTAUTH_SSO=${ssoCookie(authTime = pinnedAuthTime)}")
            }

            val code = authCodeRepo.all().single().code

            // Exchange the code for tokens — TokenPort must receive the authTime
            client.submitForm(
                url = "/t/acme/protocol/openid-connect/token",
                formParameters =
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", "https://app.example.com/callback")
                        append("client_id", "spa-app")
                        append("code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
                    },
            )

            assertEquals(
                pinnedAuthTime.epochSecond,
                tokenPort.lastAuthTime?.epochSecond,
                "TokenPort.issueUserTokens must receive the auth code's authTime so the ID token can carry auth_time",
            )
        }
}
