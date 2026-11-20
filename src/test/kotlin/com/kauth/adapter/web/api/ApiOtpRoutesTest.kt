package com.kauth.adapter.web.api

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailOtpChallengeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.fakes.FakeWebhookSender
import com.kauth.infrastructure.ApiKeyPrincipal
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiOtpRoutesTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val challenges = FakeEmailOtpChallengeRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val roles = FakeRoleRepository()
    private val email = FakeEmailPort()
    private val audit = FakeAuditLogPort()
    private val resources = FakeResourceServerRepository()
    private val apiKeys = FakeApiKeyRepository()
    private val mfaRepository = com.kauth.fakes.FakeMfaRepository()
    private val signer = com.kauth.infrastructure.EncryptionService("test-secret-key-32-chars-minimum-len")
    private val totpSecret =
        com.kauth.infrastructure.TotpUtil
            .generateSecret()

    private val apiKeyService = ApiKeyService(apiKeys, tenants)
    private val otpService =
        EmailOtpService(
            tenantRepository = tenants,
            userRepository = users,
            challengeRepository = challenges,
            applicationRepository = apps,
            authorizationCodeRepository = authCodes,
            emailPort = email,
            auditLog = audit,
            resourceServerRepository = resources,
            roleRepository = roles,
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private lateinit var sendKey: String
    private lateinit var verifyKey: String

    @BeforeTest
    fun setUp() {
        tenants.clear()
        users.clear()
        challenges.clear()
        apps.clear()
        authCodes.clear()
        roles.clear()
        email.clear()
        audit.clear()
        resources.clear()
        apiKeys.clear()
        mfaRepository.clear()

        val tenant =
            tenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme",
                    issuerUrl = null,
                    securityConfig = SecurityConfig(emailOtpSignupEnabled = true),
                ),
            )
        apps.add(
            Application(
                id = ApplicationId(0),
                tenantId = tenant.id,
                clientId = "acme-bff",
                name = "Acme BFF",
                description = null,
                accessType = AccessType.CONFIDENTIAL,
                enabled = true,
                redirectUris = listOf("https://bff.acme.test/auth/callback"),
            ),
        )

        sendKey =
            (
                apiKeyService.create(tenant.id, "send", listOf(ApiScope.AUTH_SEND_OTP, ApiScope.AUTH_VERIFY_OTP))
                    as ApiKeyResult.Success
            ).value.rawKey
        verifyKey = sendKey
    }

    @Test
    fun `send-otp returns 202 and challenge id for a fresh email`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val response =
                client.post("/t/acme/api/v1/auth/send-otp") {
                    bearerAuth(sendKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"email":"alice@acme.test","originatingClientId":"acme-bff","resources":["orders-api"]}""",
                    )
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertTrue(""""challengeId"""" in response.bodyAsText())
            assertEquals(1, email.otps.size)
            assertEquals(listOf("orders-api"), challenges.all().single().resources)
        }

    @Test
    fun `send-otp rejects with 403 when the key lacks the scope`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val unscoped =
                (
                    apiKeyService.create(
                        TenantId(1),
                        "rd",
                        listOf(ApiScope.USERS_READ),
                    ) as ApiKeyResult.Success
                ).value.rawKey
            val response =
                client.post("/t/acme/api/v1/auth/send-otp") {
                    bearerAuth(unscoped)
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"x@y.test"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `send-otp returns 429 when the per-email limiter blocks`() =
        testApplication {
            val blocker =
                object : RateLimiterPort {
                    override val maxRequests = 0
                    override val windowSeconds = 60L

                    override fun isAllowed(key: String) = false

                    override fun remaining(key: String) = 0

                    override fun reset(key: String) = Unit
                }
            application { installApp(blocker, AlwaysAllowLimiter()) }
            val response =
                client.post("/t/acme/api/v1/auth/send-otp") {
                    bearerAuth(sendKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"alice@acme.test"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }

    @Test
    fun `verify-otp returns 200 with an authorization code on the happy path`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            client.post("/t/acme/api/v1/auth/send-otp") {
                bearerAuth(sendKey)
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alice@acme.test","originatingClientId":"acme-bff"}""")
            }
            val sent = email.otps.single()
            val challengeId = challenges.all().single().challengeId

            val verify =
                client.post("/t/acme/api/v1/auth/verify-otp") {
                    bearerAuth(verifyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"challengeId":"$challengeId","otp":"${sent.code}"}""")
                }
            assertEquals(HttpStatusCode.OK, verify.status)
            assertTrue(""""authorizationCode"""" in verify.bodyAsText())
        }

    @Test
    fun `verify-otp returns 410 Gone for an expired challenge`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            // Create a challenge that's already past expiry.
            val tenant = tenants.findBySlug("acme")!!
            val user =
                users.add(
                    com.kauth.domain.model.User(
                        tenantId = tenant.id,
                        username = "ghost",
                        email = "ghost@acme.test",
                        fullName = "Ghost",
                        passwordHash = "x",
                    ),
                )
            challenges.create(
                com.kauth.domain.model.EmailOtpChallenge(
                    userId = user.id!!,
                    tenantId = tenant.id,
                    challengeId = "expired-handle",
                    codeHash =
                        com.kauth.domain.util
                            .sha256Hex("123456"),
                    expiresAt =
                        java.time.Instant
                            .now()
                            .minusSeconds(60),
                ),
            )

            val verify =
                client.post("/t/acme/api/v1/auth/verify-otp") {
                    bearerAuth(verifyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"challengeId":"expired-handle","otp":"123456"}""")
                }
            assertEquals(HttpStatusCode.Gone, verify.status)
        }

    @Test
    fun `verify-otp returns 422 invalid_otp on wrong code`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            client.post("/t/acme/api/v1/auth/send-otp") {
                bearerAuth(sendKey)
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alice@acme.test","originatingClientId":"acme-bff"}""")
            }
            val challengeId = challenges.all().single().challengeId

            val verify =
                client.post("/t/acme/api/v1/auth/verify-otp") {
                    bearerAuth(verifyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"challengeId":"$challengeId","otp":"999999"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, verify.status)
            assertTrue("""invalid_otp""" in verify.bodyAsText())
            assertEquals("application/problem+json", verify.headers["Content-Type"])
        }

    @Test
    fun `verify-otp rejects a challenge from a different tenant`() =
        testApplication {
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val otherTenant =
                tenants.add(
                    Tenant(
                        id = TenantId(0),
                        slug = "other",
                        displayName = "Other",
                        issuerUrl = null,
                    ),
                )
            val otherUser =
                users.add(
                    com.kauth.domain.model.User(
                        tenantId = otherTenant.id,
                        username = "alien",
                        email = "alien@other.test",
                        fullName = "Alien",
                        passwordHash = "x",
                    ),
                )
            challenges.create(
                com.kauth.domain.model.EmailOtpChallenge(
                    userId = otherUser.id!!,
                    tenantId = otherTenant.id,
                    challengeId = "alien-handle",
                    codeHash =
                        com.kauth.domain.util
                            .sha256Hex("123456"),
                    expiresAt =
                        java.time.Instant
                            .now()
                            .plusSeconds(600),
                ),
            )

            val verify =
                client.post("/t/acme/api/v1/auth/verify-otp") {
                    bearerAuth(verifyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"challengeId":"alien-handle","otp":"123456"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, verify.status)
            assertTrue("""invalid_otp""" in verify.bodyAsText())
        }

    // -- Second factor -------------------------------------------------------

    private fun aliceWithTotp(): com.kauth.domain.model.User {
        val tenantId = tenants.findBySlug("acme")!!.id
        val alice =
            users.add(
                com.kauth.domain.model.User(
                    tenantId = tenantId,
                    username = "alice",
                    email = "alice@acme.test",
                    fullName = "Alice",
                    passwordHash = "",
                    emailVerified = true,
                ),
            )
        mfaRepository.saveEnrollment(
            com.kauth.domain.model.MfaEnrollment(
                userId = alice.id!!,
                tenantId = tenantId,
                secret = totpSecret,
                verified = true,
            ),
        )
        return alice
    }

    private suspend fun ApplicationTestBuilder.verifyEmailCode(): HttpResponse {
        client.post("/t/acme/api/v1/auth/send-otp") {
            bearerAuth(sendKey)
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@acme.test","originatingClientId":"acme-bff"}""")
        }
        val challengeId = challenges.all().single().challengeId
        return client.post("/t/acme/api/v1/auth/verify-otp") {
            bearerAuth(verifyKey)
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challengeId","otp":"${email.otps.single().code}"}""")
        }
    }

    private fun mfaTokenFrom(response: String): String =
        kotlinx.serialization.json.Json
            .parseToJsonElement(response)
            .let { it as kotlinx.serialization.json.JsonObject }["mfaToken"]
            .toString()
            .trim('"')

    private suspend fun ApplicationTestBuilder.verifyMfa(
        token: String,
        code: String,
    ) = client.post("/t/acme/api/v1/auth/verify-mfa") {
        bearerAuth(verifyKey)
        contentType(ContentType.Application.Json)
        setBody("""{"mfaToken":"$token","code":"$code"}""")
    }

    @Test
    fun `verify-otp asks for the second factor instead of issuing a code when the user has TOTP`() =
        testApplication {
            aliceWithTotp()
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }

            val verify = verifyEmailCode()

            assertEquals(HttpStatusCode.Forbidden, verify.status)
            val body = verify.bodyAsText()
            assertTrue(""""error":"mfa_required"""" in body, body)
            assertTrue(""""mfaRequirements":["otp","recovery-code"]""" in body, body)
            assertTrue(mfaTokenFrom(body).isNotBlank())
            assertTrue(authCodes.all().isEmpty(), "No code may exist before the second factor")
        }

    @Test
    fun `verify-mfa with a TOTP code returns the authorization code`() =
        testApplication {
            aliceWithTotp()
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val token = mfaTokenFrom(verifyEmailCode().bodyAsText())

            val response =
                verifyMfa(
                    token,
                    com.kauth.infrastructure.TotpUtil
                        .generateCode(totpSecret),
                )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(""""authorizationCode"""" in response.bodyAsText())
            assertEquals(1, authCodes.all().size)
        }

    @Test
    fun `verify-mfa accepts a recovery code`() =
        testApplication {
            val alice = aliceWithTotp()
            mfaRepository.saveRecoveryCodes(
                listOf(
                    com.kauth.domain.model.MfaRecoveryCode(
                        userId = alice.id!!,
                        tenantId = alice.tenantId,
                        codeHash =
                            com.kauth.fakes
                                .FakePasswordHasher()
                                .hash("recovery-code-one"),
                    ),
                ),
            )
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val token = mfaTokenFrom(verifyEmailCode().bodyAsText())

            val response = verifyMfa(token, "recovery-code-one")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }

    @Test
    fun `verify-mfa refuses a wrong code and issues nothing`() =
        testApplication {
            aliceWithTotp()
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val token = mfaTokenFrom(verifyEmailCode().bodyAsText())

            val response = verifyMfa(token, "000000")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `verify-mfa refuses a forged or tampered token`() =
        testApplication {
            aliceWithTotp()
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }
            val token = mfaTokenFrom(verifyEmailCode().bodyAsText())
            val code =
                com.kauth.infrastructure.TotpUtil
                    .generateCode(totpSecret)

            assertEquals(HttpStatusCode.Unauthorized, verifyMfa("not-a-token", code).status)
            assertEquals(HttpStatusCode.Unauthorized, verifyMfa(token.replaceFirst("otp-mfa", "otp-mfb"), code).status)
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `verify-otp refuses when the workspace requires MFA the user lacks`() =
        testApplication {
            val acme = tenants.findBySlug("acme")!!
            tenants.update(acme.copy(securityConfig = acme.securityConfig.copy(mfaPolicy = "required")))
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter()) }

            val verify = verifyEmailCode()

            assertEquals(HttpStatusCode.Forbidden, verify.status)
            assertTrue("mfa_enrollment_required" in verify.bodyAsText())
            assertTrue(authCodes.all().isEmpty())
        }

    @Test
    fun `without a signing key a user who owes MFA is refused, not let through`() =
        testApplication {
            aliceWithTotp()
            application { installApp(AlwaysAllowLimiter(), AlwaysAllowLimiter(), encryptionService = null) }

            val verify = verifyEmailCode()

            assertEquals(HttpStatusCode.ServiceUnavailable, verify.status)
            assertTrue(authCodes.all().isEmpty())
        }

    private fun stubSelfService(audit: com.kauth.fakes.FakeAuditLogPort) =
        com.kauth.domain.service.CredentialFlowService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = com.kauth.fakes.FakeSessionRepository(),
            passwordHasher = com.kauth.fakes.FakePasswordHasher(),
            auditLog = audit,
            evTokenRepo = com.kauth.fakes.FakeEmailVerificationTokenRepository(),
            prTokenRepo = com.kauth.fakes.FakePasswordResetTokenRepository(),
            emailPort = com.kauth.fakes.FakeEmailPort(),
            emailScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

    private fun stubAdminUserService(
        tenants: com.kauth.fakes.FakeTenantRepository,
        users: com.kauth.fakes.FakeUserRepository,
        audit: com.kauth.fakes.FakeAuditLogPort,
    ) = com.kauth.domain.service.AdminUserService(
        tenantRepository = tenants,
        userRepository = users,
        sessionRepository = com.kauth.fakes.FakeSessionRepository(),
        passwordHasher = com.kauth.fakes.FakePasswordHasher(),
        auditLog = audit,
        credentialFlowService = stubSelfService(audit),
        collisionCheck =
            com.kauth.domain.service
                .IdentifierCollisionCheck(users),
        usernameGenerator =
            com.kauth.domain.service
                .UsernameGenerator(users),
    )

    private fun stubMfaService(
        tenants: com.kauth.fakes.FakeTenantRepository,
        users: com.kauth.fakes.FakeUserRepository,
        audit: com.kauth.fakes.FakeAuditLogPort,
    ) = com.kauth.domain.service.MfaService(
        mfaRepository = mfaRepository,
        userRepository = users,
        tenantRepository = tenants,
        passwordHasher = com.kauth.fakes.FakePasswordHasher(),
        auditLog = audit,
    )

    private fun io.ktor.server.application.Application.installApp(
        emailLimiter: RateLimiterPort,
        ipLimiter: RateLimiterPort,
        encryptionService: com.kauth.infrastructure.EncryptionService? = signer,
    ) {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            bearer("api-key") {
                realm = "KotAuth REST API"
                authenticate { creds ->
                    if (creds.token.startsWith("kauth_")) ApiKeyPrincipal(rawToken = creds.token) else null
                }
            }
        }
        routing {
            apiRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenants,
                roleRepository = roles,
                groupRepository = com.kauth.fakes.FakeGroupRepository(),
                applicationRepository = apps,
                sessionRepository = com.kauth.fakes.FakeSessionRepository(),
                auditLogRepository = com.kauth.fakes.FakeAuditLogRepository(),
                roleGroupService =
                    com.kauth.domain.service.RoleGroupService(
                        roleRepository = roles,
                        groupRepository = com.kauth.fakes.FakeGroupRepository(),
                        tenantRepository = tenants,
                        userRepository = users,
                        applicationRepository = apps,
                        auditLog = audit,
                    ),
                accountService =
                    com.kauth.domain.service.AdminAccountService(
                        tenantRepository = tenants,
                        userRepository = users,
                        auditLog = audit,
                        credentialFlowService = stubSelfService(audit),
                    ),
                adminUserService = stubAdminUserService(tenants, users, audit),
                mfaService = stubMfaService(tenants, users, audit),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = apps,
                        tenantRepository = tenants,
                        passwordHasher = com.kauth.fakes.FakePasswordHasher(),
                        auditLog = audit,
                    ),
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = users,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
                emailOtpService = otpService,
                otpEmailRateLimiter = emailLimiter,
                otpIpRateLimiter = ipLimiter,
                apiWriteRateLimiter = AlwaysAllowLimiter(),
                apiReadRateLimiter = AlwaysAllowLimiter(),
                webhookService =
                    WebhookService(
                        FakeWebhookEndpointRepository(),
                        FakeWebhookDeliveryRepository(),
                        FakeWebhookSender(),
                    ),
                resourceServerService = ResourceServerService(FakeResourceServerRepository()),
                webAuthnService =
                    WebAuthnService(
                        credentialRepository = FakeWebAuthnCredentialRepository(),
                        relyingParty = FakeRelyingPartyAdapter(),
                        secretKey = "test-secret-key-32chars-long-xxxx",
                        auditLog = FakeAuditLogPort(),
                        userRepository = FakeUserRepository(),
                    ),
                webAuthnCredentialRepository = FakeWebAuthnCredentialRepository(),
                userRepository = users,
                transactionRunner = com.kauth.fakes.FakeTransactionRunner(),
                encryptionService = encryptionService,
                mfaRateLimiter = AlwaysAllowLimiter(),
            )
        }
    }
}
