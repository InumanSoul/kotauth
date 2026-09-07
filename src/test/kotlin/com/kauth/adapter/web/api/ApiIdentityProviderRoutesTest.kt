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
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(orianaBody())
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "The create response must not carry the client secret: $body")
            assertTrue("\"key\":\"oriana\"" in body)
            assertTrue("\"kind\":\"oidc\"" in body)

            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals("oriana-client", stored?.clientId)
            assertEquals(SECRET, stored?.clientSecret, "The secret must still reach the row it was sent for")
            assertEquals(ProviderKind.OIDC, stored?.kind)
        }

    @Test
    fun `PUT over an existing provider returns 200 and keeps the stored secret when it is omitted`() =
        testApplication {
            application { installTestApp() }
            seedOriana()

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"oriana-client-2","issuer":"https://example.oriana.com","enabled":false}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(SECRET in response.bodyAsText())
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals("oriana-client-2", stored?.clientId)
            assertEquals(false, stored?.enabled)
            assertEquals(SECRET, stored?.clientSecret, "An omitted secret must keep the stored one, not blank it")
        }

    @Test
    fun `PUT an OIDC provider with no issuer is rejected and the message names the issuer`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"clientId":"oriana-client","clientSecret":"$SECRET"}""")
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
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"clientId":"c","clientSecret":"$SECRET","issuer":"http://example.oriana.com"}""",
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
                client.put("/t/acme/api/v1/identity-providers/Oriana%20Inc") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(orianaBody())
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `PUT an unknown kind is rejected`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
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
            seedOriana()

            val response = client.get("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "A single-provider read must not carry the secret: $body")
            assertTrue("\"clientId\":\"oriana-client\"" in body, "…but must still carry the rest of the row: $body")
        }

    @Test
    fun `GET of the list never carries the client secret`() =
        testApplication {
            application { installTestApp() }
            seedOriana()

            val response = client.get("/t/acme/api/v1/identity-providers") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(SECRET in body, "A list read must not carry the secret: $body")
            assertTrue("\"key\":\"oriana\"" in body)
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
                // The two JIT columns are readable on purpose: an operator configuring this
                // resource from Terraform has to be able to see whether auto-creation is on and
                // for which domains. Neither can hold a secret — one is a flag, one a domain list.
                "jitEnabled",
                "jitAllowedDomains",
                "createdAt",
                "updatedAt",
            ),
            elements,
        )
    }

    // =========================================================================
    // Just-in-time provisioning over the API
    // =========================================================================

    @Test
    fun `PUT sets just-in-time provisioning and its allowed domains`() =
        testApplication {
            application { installTestApp() }
            seedOriana()

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"oriana-client","issuer":"https://example.oriana.com",
                         "jitEnabled":true,"jitAllowedDomains":["  Acme.COM  ","acme.com","partner.example"]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals(true, stored?.jitEnabled)
            // Trimmed, lower-cased and de-duplicated by IdentityProviderService, exactly as the
            // admin form's chips are. A second normaliser on this surface is a second set of rules
            // for the two surfaces to disagree about.
            assertEquals(listOf("acme.com", "partner.example"), stored?.jitAllowedDomains)
        }

    @Test
    fun `a provider read carries its just-in-time settings`() =
        testApplication {
            application { installTestApp() }
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val body =
                client
                    .get("/t/acme/api/v1/identity-providers/oriana") {
                        bearerAuth(readKey)
                    }.bodyAsText()

            assertTrue("\"jitEnabled\":true" in body, "A read must show whether auto-creation is on: $body")
            assertTrue("acme.example" in body, "A read must show which domains it is on for: $body")
            assertFalse(SECRET in body, "Adding readable fields must not make the secret readable")
        }

    @Test
    fun `PUT that omits the just-in-time fields leaves them as they were`() =
        testApplication {
            application { installTestApp() }
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"clientId":"oriana-client-2","issuer":"https://example.oriana.com"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals("oriana-client-2", stored?.clientId, "The update the caller did ask for must land")
            // Absent is not empty. Conflating them turns renaming a client into "auto-creation is
            // now off for everyone", which no caller asked for and no response would announce.
            assertEquals(true, stored?.jitEnabled)
            assertEquals(listOf("acme.example"), stored?.jitAllowedDomains)
        }

    @Test
    fun `PUT with an empty allowed-domain list clears it`() =
        testApplication {
            application { installTestApp() }
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"oriana-client","issuer":"https://example.oriana.com","jitAllowedDomains":[]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // The other half of the same distinction: an explicit empty list is how a caller says
            // "stop creating accounts automatically", and it has to stay expressible.
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals(emptyList(), stored?.jitAllowedDomains)
        }

    @Test
    fun `PUT with a domain that is not a domain is rejected and stores nothing`() =
        testApplication {
            application { installTestApp() }
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(writeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"clientId":"oriana-client","issuer":"https://example.oriana.com",
                         "jitAllowedDomains":["someone@acme.example"]}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue("someone@acme.example" in response.bodyAsText(), "The rejection must name the cause")
            val stored = idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!)
            assertEquals(listOf("acme.example"), stored?.jitAllowedDomains, "A rejected write must change nothing")
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
                    provider = ProviderKey.of("oriana")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.oriana.com",
                ),
            )

            val response = client.get("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(readKey) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertFalse(SECRET in response.bodyAsText())
        }

    @Test
    fun `the list only carries this workspace's providers`() =
        testApplication {
            application { installTestApp() }
            seedOriana()
            idpRepo.add(
                IdentityProvider(
                    tenantId = TenantId(2),
                    provider = ProviderKey.of("workforce")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.example.com",
                ),
            )

            val body = client.get("/t/acme/api/v1/identity-providers") { bearerAuth(readKey) }.bodyAsText()

            assertTrue("\"key\":\"oriana\"" in body)
            assertFalse("workforce" in body, "A workspace must not see another workspace's providers: $body")
        }

    @Test
    fun `DELETE cannot reach a provider in another workspace`() =
        testApplication {
            application { installTestApp() }
            idpRepo.add(
                IdentityProvider(
                    tenantId = TenantId(2),
                    provider = ProviderKey.of("oriana")!!,
                    clientId = "other-client",
                    clientSecret = SECRET,
                    kind = ProviderKind.OIDC,
                    issuer = "https://other.oriana.com",
                ),
            )

            val response = client.delete("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(writeKey) }

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
            seedOriana()
            val noScopeKey = createKey("No Scope", ApiScope.USERS_READ)

            val response = client.get("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(noScopeKey) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertFalse(SECRET in response.bodyAsText())
        }

    @Test
    fun `PUT returns 403 when only the read scope is held`() =
        testApplication {
            application { installTestApp() }

            val response =
                client.put("/t/acme/api/v1/identity-providers/oriana") {
                    bearerAuth(readKey)
                    contentType(ContentType.Application.Json)
                    setBody(orianaBody())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(idpRepo.findAllByTenant(TenantId(1)).isEmpty())
        }

    @Test
    fun `DELETE returns 403 when only the read scope is held`() =
        testApplication {
            application { installTestApp() }
            seedOriana()

            val response = client.delete("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(readKey) }

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
            seedOriana()

            val response = client.delete("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(writeKey) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(idpRepo.findByTenantAndProvider(TenantId(1), ProviderKey.of("oriana")!!))
        }

    @Test
    fun `DELETE of a provider that was never configured is a 404`() =
        testApplication {
            application { installTestApp() }

            val response = client.delete("/t/acme/api/v1/identity-providers/oriana") { bearerAuth(writeKey) }

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

    private fun seedOriana(
        jitEnabled: Boolean = false,
        jitAllowedDomains: List<String> = emptyList(),
    ) {
        idpRepo.add(
            IdentityProvider(
                tenantId = TenantId(1),
                provider = ProviderKey.of("oriana")!!,
                clientId = "oriana-client",
                clientSecret = SECRET,
                kind = ProviderKind.OIDC,
                issuer = "https://example.oriana.com",
                jitEnabled = jitEnabled,
                jitAllowedDomains = jitAllowedDomains,
            ),
        )
    }

    private fun orianaBody() =
        """{"clientId":"oriana-client","clientSecret":"$SECRET","issuer":"https://example.oriana.com"}"""

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
                        collisionCheck =
                            com.kauth.domain.service
                                .IdentifierCollisionCheck(userRepo),
                        usernameGenerator =
                            com.kauth.domain.service
                                .UsernameGenerator(userRepo),
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
        const val SECRET = "s3cr3t-oriana-client-secret"
    }
}
