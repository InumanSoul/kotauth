package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.IdentityProviderService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.WebAuthnService
import com.kauth.domain.service.WebhookService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.fakes.FakeWebhookDeliveryRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.CachingClaimMapperService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the identity-provider REST API.
 *
 * The recurring assertion is negative: `SECRET` must not appear in the raw body of any read.
 * A field-level assertion would pass against a response that renamed the field or nested it,
 * so the body text is what the tests look at.
 */
class ApiIdentityProviderRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val idpRepo = FakeIdentityProviderRepository()
    private val hasher = FakePasswordHasher()

    private val apiKeyService =
        ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo)
    private val identityProviderService = IdentityProviderService(idpRepo)

    private val tenant =
        Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null, theme = TenantTheme.DEFAULT)
    private val otherTenant =
        Tenant(id = TenantId(2), slug = "other", displayName = "Other", issuerUrl = null, theme = TenantTheme.DEFAULT)

    private var readKey: String = ""
    private var writeKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        apiKeyRepo.clear()
        idpRepo.clear()

        tenantRepo.add(tenant)
        tenantRepo.add(otherTenant)
        // Dummy user needed to spin up the shared dependencies inside installTestApp().
        userRepo.add(
            User(
                id = UserId(1),
                tenantId = TenantId(1),
                username = "admin",
                email = "admin@acme.com",
                fullName = "Admin",
                passwordHash = hasher.hash("pw"),
            ),
        )

        readKey = createKey("Read", ApiScope.IDENTITY_PROVIDERS_READ)
        writeKey = createKey("Write", ApiScope.IDENTITY_PROVIDERS_READ, ApiScope.IDENTITY_PROVIDERS_WRITE)
    }

    // =========================================================================
    // Writes
    // =========================================================================

    @Test
    fun `PUT creates an OIDC provider, returns 201, and does not echo the secret`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(oktaBody())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "The create response must not carry the client secret: $body")
            assertTrue("\"key\":\"okta\"" in body)
            assertTrue("\"kind\":\"oidc\"" in body)

            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("okta")!!)
            assertEquals("okta-client", stored?.clientId)
            assertEquals(SECRET, stored?.clientSecret, "The secret must still reach the row it was sent for")
            assertEquals(ProviderKind.OIDC, stored?.kind)
        }

    @Test
    fun `PUT over an existing provider returns 200 and keeps the stored secret when it is omitted`() =
        testApplication {
            application { installTestApp() }
            seedOkta()

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"okta-client-2","issuer":"https://example.okta.com","enabled":false}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(SECRET in response.bodyAsText())
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("okta")!!)
            assertEquals("okta-client-2", stored?.clientId)
            assertEquals(false, stored?.enabled)
            assertEquals(SECRET, stored?.clientSecret, "An omitted secret must keep the stored one, not blank it")
        }

    @Test
    fun `PUT an OIDC provider with no issuer is rejected and the message names the issuer`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"clientId":"okta-client","clientSecret":"$SECRET"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue("issuer" in response.bodyAsText(), "The rejection must name the cause: ${response.bodyAsText()}")
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty(), "A rejected write must persist nothing")
        }

    @Test
    fun `PUT an issuer that is not https is rejected`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"clientId":"c","clientSecret":"$SECRET","issuer":"http://example.okta.com"}""",
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `PUT a reserved key as an OIDC provider is rejected`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/google") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"c","clientSecret":"$SECRET","kind":"oidc","issuer":"https://accounts.google.com"}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `PUT a malformed provider key is rejected before anything is written`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/Okta%20Inc") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(oktaBody())
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `PUT an unknown kind is rejected`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"clientId":"c","clientSecret":"$SECRET","kind":"saml"}""")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    // =========================================================================
    // Reads never carry the secret
    // =========================================================================

    @Test
    fun `GET of one provider never carries the client secret`() =
        testApplication {
            application { installTestApp() }
            seedOkta()

            val response = client.get("/t/acme/api/v1/identity-providers/okta") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "A single-provider read must not carry the secret: $body")
            assertTrue("\"clientId\":\"okta-client\"" in body, "…but must still carry the rest of the row: $body")
        }

    @Test
    fun `GET of the list never carries the client secret`() =
        testApplication {
            application { installTestApp() }
            seedOkta()

            val response = client.get("/t/acme/api/v1/identity-providers") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "A list read must not carry the secret: $body")
            assertTrue("\"key\":\"okta\"" in body)
        }

    @Test
    fun `the response type has no element that could carry a secret`() {
        val descriptor = IdentityProviderDto.serializer().descriptor
        val elements = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

        // An exact set, not a "no field named secret" check: a field added under any name is a
        // new value on the wire and has to be a deliberate decision, not a silent one.
        assertEquals(
            listOf(
                "key",
                "kind",
                "clientId",
                "enabled",
                "displayName",
                "issuer",
                "authorizationEndpoint",
                "tokenEndpoint",
                "jwksUri",
                "scopes",
                "createdAt",
                "updatedAt",
            ),
            elements,
        )
    }

    // =========================================================================
    // Tenant scoping
    // =========================================================================

    @Test
    fun `GET of a provider that belongs to another workspace is a 404`() =
        testApplication {
            application { installTestApp() }
            idpRepo.add(
                IdentityProvider(
                    tenantId = TenantId(2),
                    provider = ProviderKey.of("okta")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.okta.com",
                ),
            )

            val response = client.get("/t/acme/api/v1/identity-providers/okta") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertFalse(SECRET in response.bodyAsText())
        }

    @Test
    fun `the list only carries this workspace's providers`() =
        testApplication {
            application { installTestApp() }
            seedOkta()
            idpRepo.add(
                IdentityProvider(
                    tenantId = TenantId(2),
                    provider = ProviderKey.of("entra")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.example.com",
                ),
            )

            val body = client.get("/t/acme/api/v1/identity-providers") { bearerAuth(readKey) }.bodyAsText()

            assertTrue("\"key\":\"okta\"" in body)
            assertFalse("entra" in body, "A workspace must not see another workspace's providers: $body")
        }

    @Test
    fun `DELETE cannot reach a provider in another workspace`() =
        testApplication {
            application { installTestApp() }
            idpRepo.add(
                IdentityProvider(
                    tenantId = TenantId(2),
                    provider = ProviderKey.of("okta")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.okta.com",
                ),
            )

            val response = client.delete("/t/acme/api/v1/identity-providers/okta") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(1, idpRepo.findAllByTenant(TenantId(2)).size, "The other workspace's row must survive")
        }

    // =========================================================================
    // Scope enforcement
    // =========================================================================

    @Test
    fun `GET the list returns 403 when the read scope is missing`() =
        testApplication {
            application { installTestApp() }
            val noScopeKey = createKey("No Scope", ApiScope.USERS_READ)

            val response = client.get("/t/acme/api/v1/identity-providers") { bearerAuth(noScopeKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET one provider returns 403 when the read scope is missing`() =
        testApplication {
            application { installTestApp() }
            seedOkta()
            val noScopeKey = createKey("No Scope", ApiScope.USERS_READ)

            val response = client.get("/t/acme/api/v1/identity-providers/okta") { bearerAuth(noScopeKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertFalse(SECRET in response.bodyAsText())
        }

    @Test
    fun `PUT returns 403 when only the read scope is held`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/okta") {
                    bearerAuth(readKey)
                    contentType(ContentType.Application.Json)
                    setBody(oktaBody())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE returns 403 when only the read scope is held`() =
        testApplication {
            application { installTestApp() }
            seedOkta()

            val response = client.delete("/t/acme/api/v1/identity-providers/okta") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(1, idpRepo.findAllByTenant(TenantId(1)).size)
        }

    // =========================================================================
    // Delete
    // =========================================================================

    @Test
    fun `DELETE removes the provider and returns 204`() =
        testApplication {
            application { installTestApp() }
            seedOkta()

            val response = client.delete("/t/acme/api/v1/identity-providers/okta") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("okta")!!))
        }

    @Test
    fun `DELETE of a provider that was never configured is a 404`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/identity-providers/okta") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createKey(
        name: String,
        vararg scopes: String,
    ): String =
        (
            apiKeyService.create(
                tenantId = TenantId(1),
                name = name,
                scopes = scopes.toList(),
            ) as ApiKeyResult.Success
        ).value.rawKey

    private fun seedOkta() {
        idpRepo.add(
            IdentityProvider(
                tenantId = TenantId(1),
                provider = ProviderKey.of("okta")!!,
                clientId = "okta-client",
                clientSecret = SECRET,
                kind = ProviderKind.OIDC,
                issuer = "https://example.okta.com",
            ),
        )
    }

    private fun oktaBody() =
        """{"clientId":"okta-client","clientSecret":"$SECRET","issuer":"https://example.okta.com"}"""

    private fun buildFakeSelfService() =
        com.kauth.domain.service.CredentialFlowService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = com.kauth.fakes.FakeSessionRepository(),
            passwordHasher = hasher,
            auditLog = com.kauth.fakes.FakeAuditLogPort(),
            evTokenRepo = com.kauth.fakes.FakeEmailVerificationTokenRepository(),
            prTokenRepo = com.kauth.fakes.FakePasswordResetTokenRepository(),
            emailPort = com.kauth.fakes.FakeEmailPort(),
            emailScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

    private fun io.ktor.server.application.Application.installTestApp() {
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
                tenantRepository = tenantRepo,
                roleRepository = com.kauth.fakes.FakeRoleRepository(),
                groupRepository = com.kauth.fakes.FakeGroupRepository(),
                applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                sessionRepository = com.kauth.fakes.FakeSessionRepository(),
                auditLogRepository = com.kauth.fakes.FakeAuditLogRepository(),
                roleGroupService =
                    com.kauth.domain.service.RoleGroupService(
                        roleRepository = com.kauth.fakes.FakeRoleRepository(),
                        groupRepository = com.kauth.fakes.FakeGroupRepository(),
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
                accountService =
                    com.kauth.domain.service.AdminAccountService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                        credentialFlowService = buildFakeSelfService(),
                    ),
                adminUserService =
                    com.kauth.domain.service.AdminUserService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        sessionRepository = com.kauth.fakes.FakeSessionRepository(),
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                        credentialFlowService = buildFakeSelfService(),
                    ),
                mfaService =
                    com.kauth.domain.service.MfaService(
                        mfaRepository = com.kauth.fakes.FakeMfaRepository(),
                        userRepository = userRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = com.kauth.fakes.FakeAuditLogPort(),
                    ),
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService = CachingClaimMapperService(mapperRepository = FakeTenantClaimMapperRepository()),
                emailOtpService = stubEmailOtpService(),
                otpEmailRateLimiter = AlwaysAllowLimiter(),
                otpIpRateLimiter = AlwaysAllowLimiter(),
                apiWriteRateLimiter = AlwaysAllowLimiter(),
                apiReadRateLimiter = AlwaysAllowLimiter(),
                webhookService = WebhookService(FakeWebhookEndpointRepository(), FakeWebhookDeliveryRepository()),
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
                userRepository = userRepo,
                transactionRunner = com.kauth.fakes.FakeTransactionRunner(),
                identityProviderService = identityProviderService,
            )
        }
    }

    private companion object {
        /** Distinctive enough that finding it anywhere in a response body is unambiguous. */
        const val SECRET = "s3cr3t-okta-client-secret"
    }
}
