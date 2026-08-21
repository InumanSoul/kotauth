package com.kauth.adapter.web.auth

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.WebAuthnService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.EnglishOnlyTranslation
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PasskeyAuthRoutesTest {
    private val encryptionService = EncryptionService("test-secret-key-32chars-long-xxxx")
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val credentialRepo = FakeWebAuthnCredentialRepository()
    private val relyingParty = FakeRelyingPartyAdapter()
    private val auditLog = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val sessions = FakeSessionRepository()
    private val roles = FakeRoleRepository()
    private val tokenPort = FakeTokenPort()
    private val limiter = InMemoryRateLimiter(maxRequests = 1000, windowSeconds = 60)
    private val blockedLimiter = InMemoryRateLimiter(maxRequests = 0, windowSeconds = 60)

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            passkeysEnabled = true,
        )

    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.dev",
            fullName = "Alice",
            passwordHash = hasher.hash("secret"),
            enabled = true,
        )

    private val seededCredential =
        WebAuthnCredential(
            id = 1L,
            userId = UserId(10),
            tenantId = TenantId(1),
            credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
            publicKeyCose = ByteArray(77) { it.toByte() },
            signCounter = 0L,
            aaguid = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            transports = listOf("internal"),
            name = "Alice's Key",
            backupEligible = true,
            backupState = false,
            createdAt = Instant.now(),
            lastUsedAt = null,
        )

    private fun buildWebAuthnService() =
        WebAuthnService(
            credentialRepository = credentialRepo,
            relyingParty = relyingParty,
            secretKey = "test-secret-key-32chars-long-xxxx",
            auditLog = auditLog,
            userRepository = userRepo,
        )

    private fun buildOAuthService() =
        OAuthService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = apps,
            sessionRepository = sessions,
            authCodeRepository = authCodes,
            tokenPort = tokenPort,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        credentialRepo.clear()
        auditLog.clear()
        apps.clear()
        authCodes.clear()
        sessions.clear()
        roles.clear()
        tenantRepo.add(tenant)
        userRepo.add(alice)
        credentialRepo.save(seededCredential)
        relyingParty.throwOnFinishAssertion = null
    }

    private fun appBlock(
        rateLimiter: InMemoryRateLimiter = limiter,
    ): io.ktor.server.application.Application.() -> Unit =
        {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    authService =
                        com.kauth.domain.service.AuthService(
                            userRepository = userRepo,
                            tenantRepository = tenantRepo,
                            tokenPort = tokenPort,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            sessionRepository = sessions,
                        ),
                    oauthService = buildOAuthService(),
                    tenantRepository = tenantRepo,
                    loginRateLimiter = limiter,
                    registerRateLimiter = limiter,
                    tokenRateLimiter = limiter,
                    credentialFlowService =
                        com.kauth.domain.service.CredentialFlowService(
                            userRepository = userRepo,
                            tenantRepository = tenantRepo,
                            sessionRepository = sessions,
                            passwordHasher = hasher,
                            auditLog = auditLog,
                            evTokenRepo = com.kauth.fakes.FakeEmailVerificationTokenRepository(),
                            prTokenRepo = com.kauth.fakes.FakePasswordResetTokenRepository(),
                            emailPort = com.kauth.fakes.FakeEmailPort(),
                            passwordPolicy = com.kauth.fakes.FakePasswordPolicyPort(),
                            emailScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                        ),
                    encryptionService = encryptionService,
                    translationPort = EnglishOnlyTranslation(),
                    webAuthnService = buildWebAuthnService(),
                    passkeyRateLimiter = rateLimiter,
                )
            }
        }

    // =========================================================================
    // POST /passkeys/authenticate/start
    // =========================================================================

    @Test
    fun `POST passkeys authenticate start returns options and sets challenge cookie`() =
        testApplication {
            application(appBlock())
            val response = client.post("/t/acme/passkeys/authenticate/start")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["publicKey"], "Response must contain publicKey field")
            val setCookie = response.headers[HttpHeaders.SetCookie].orEmpty()
            assertTrue(setCookie.contains("KOTAUTH_PASSKEY_AUTH="), "Must set KOTAUTH_PASSKEY_AUTH cookie")
        }

    @Test
    fun `POST passkeys authenticate start returns 400 when passkeys disabled for tenant`() =
        testApplication {
            tenantRepo.clear()
            tenantRepo.add(tenant.copy(passkeysEnabled = false))
            application(appBlock())
            val response = client.post("/t/acme/passkeys/authenticate/start")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // =========================================================================
    // POST /passkeys/authenticate/finish
    // =========================================================================

    @Test
    fun `POST passkeys authenticate finish returns 400 when challenge cookie missing`() =
        testApplication {
            application(appBlock())
            val response =
                client.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("InvalidChallenge", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys authenticate finish returns 429 when rate limited`() =
        testApplication {
            application(appBlock(rateLimiter = blockedLimiter))
            // Obtain a valid challenge cookie first via start
            val startClient = createClient { install(HttpCookies) }
            startClient.post("/t/acme/passkeys/authenticate/start")

            val response =
                startClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }

    @Test
    fun `POST passkeys authenticate finish happy path returns user_id when no OAuth context`() =
        testApplication {
            application(appBlock())
            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            cookieClient.post("/t/acme/passkeys/authenticate/start")
            val response =
                cookieClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("10", body["user_id"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys authenticate finish returns 400 on counter replay`() =
        testApplication {
            // Seed credential with high counter (100), DEFAULT_ASSERTION_RESULT returns counter=1
            credentialRepo.clear()
            credentialRepo.save(seededCredential.copy(signCounter = 100L))
            application(appBlock())
            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            cookieClient.post("/t/acme/passkeys/authenticate/start")
            val response =
                cookieClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("CounterReplayDetected", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys authenticate finish returns 400 on tenant mismatch`() =
        testApplication {
            // Seed credential belonging to a different tenant
            credentialRepo.clear()
            credentialRepo.save(seededCredential.copy(tenantId = TenantId(999)))
            application(appBlock())
            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            cookieClient.post("/t/acme/passkeys/authenticate/start")
            val response =
                cookieClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("TenantMismatch", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys authenticate finish returns 400 when user disabled`() =
        testApplication {
            userRepo.clear()
            userRepo.add(alice.copy(enabled = false))
            application(appBlock())
            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            cookieClient.post("/t/acme/passkeys/authenticate/start")
            val response =
                cookieClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("UserDisabled", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST passkeys authenticate finish with OAuth context redirects to authorization code`() =
        testApplication {
            val app =
                com.kauth.domain.model.Application(
                    id =
                        com.kauth.domain.model
                            .ApplicationId(1),
                    tenantId = TenantId(1),
                    clientId = "spa-app",
                    name = "SPA",
                    description = null,
                    accessType = com.kauth.domain.model.AccessType.CONFIDENTIAL,
                    enabled = true,
                    redirectUris = listOf("https://app.example.com/callback"),
                    grantTypes =
                        com.kauth.domain.model.GrantType
                            .defaultsFor(com.kauth.domain.model.AccessType.CONFIDENTIAL),
                )
            apps.add(app)
            application(appBlock())

            // Use a plain client (no HttpCookies plugin) so we can control cookies manually
            val plainClient = createClient { followRedirects = false }

            // Step 1: obtain challenge cookie from start
            val startResponse = plainClient.post("/t/acme/passkeys/authenticate/start")
            val rawSetCookie = startResponse.headers[HttpHeaders.SetCookie].orEmpty()
            val challengeCookieValue = rawSetCookie.substringAfter("KOTAUTH_PASSKEY_AUTH=").substringBefore(";")

            // Step 2: build a signed auth-context cookie carrying OAuth params
            val oauthCookieValue =
                buildAuthContextCookieValue(
                    params =
                        AuthView.OAuthParams(
                            responseType = "code",
                            clientId = "spa-app",
                            redirectUri = "https://app.example.com/callback",
                            scope = "openid",
                        ),
                    encryptionService = encryptionService,
                )

            // Step 3: finish with both cookies sent manually
            val response =
                plainClient.post("/t/acme/passkeys/authenticate/finish") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"credential":{"type":"public-key","id":"${FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID}"}}""",
                    )
                    header(
                        "Cookie",
                        "KOTAUTH_PASSKEY_AUTH=$challengeCookieValue; KOTAUTH_AUTH_CONTEXT=$oauthCookieValue",
                    )
                }
            // Should respond with JSON redirect_url pointing to the callback URI
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val redirectUrl = body["redirect_url"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(
                redirectUrl.startsWith("https://app.example.com/callback?code="),
                "Expected redirect_url to callback with code, got: $redirectUrl",
            )
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a signed KOTAUTH_AUTH_CONTEXT cookie value carrying the given OAuth params.
     * Mirrors the encoding performed by [AuthHelpers.setAuthContextCookie].
     */
    private fun buildAuthContextCookieValue(
        params: AuthView.OAuthParams,
        encryptionService: EncryptionService,
    ): String {
        val payload =
            listOf(
                params.responseType ?: "",
                params.clientId ?: "",
                params.redirectUri ?: "",
                params.scope ?: "",
                params.state ?: "",
                params.codeChallenge ?: "",
                params.codeChallengeMethod ?: "",
                params.nonce ?: "",
                System.currentTimeMillis().toString(),
                "", // otpChallengeId
                "", // otpEmail
                params.resources.joinToString(","),
            ).joinToString("|")
        return encryptionService.signCookie(payload)
    }
}
