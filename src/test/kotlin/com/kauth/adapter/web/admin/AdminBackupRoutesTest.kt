package com.kauth.adapter.web.admin

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.BackupExporterService
import com.kauth.domain.service.BackupImporterService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePortalConfigRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.Pbkdf2AesGcmBackupEncryption
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
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
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [adminBackupRoutes].
 *
 * Exercises the master-admin API key auth gate, scope enforcement, and the
 * happy-path export/import round-trip. Persistence is fakes; the encryption
 * adapter is real (Pbkdf2AesGcmBackupEncryption) so we get end-to-end coverage
 * of the bkp1. envelope under HTTP — but the round-trip test uses a short
 * passphrase that PBKDF2 still tolerates (the 600k iterations apply regardless,
 * so each crypto call adds ~1s — kept to a minimum here).
 */
class AdminBackupRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val claimMappers = FakeTenantClaimMapperRepository()
    private val idps = FakeIdentityProviderRepository()
    private val tenantKeys = FakeTenantKeyRepository()
    private val themes = FakeThemeRepository()
    private val portalConfigs = FakePortalConfigRepository()
    private val attrs = FakeUserAttributeRepository()
    private val auditRepo = FakeAuditLogRepository()
    private val auditPort = FakeAuditLogPort()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val txRunner = FakeTransactionRunner()
    private val crypto = Pbkdf2AesGcmBackupEncryption()

    private val master =
        Tenant(
            id = TenantId(1),
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val acme =
        Tenant(
            id = TenantId(2),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val acmeApp =
        Application(
            id = ApplicationId(0),
            tenantId = TenantId(2),
            clientId = "acme-app",
            name = "Acme App",
            description = null,
            accessType = AccessType.PUBLIC,
            enabled = true,
            redirectUris = listOf("https://acme.example.com/cb"),
        )

    private val acmeUser =
        User(
            id = null,
            tenantId = TenantId(2),
            username = "alice",
            email = "alice@acme.example.com",
            fullName = "Alice",
            passwordHash = "\$2a\$10\$AAAAAAAAAAAAAAAAAAAAAA",
            enabled = true,
        )

    private val apiKeyService =
        ApiKeyService(
            apiKeyRepository = apiKeyRepo,
            tenantRepository = tenantRepo,
        )

    private var fullScopeKey: String = ""
    private var exportOnlyKey: String = ""
    private var noScopeKey: String = ""
    private var nonMasterKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        roleRepo.clear()
        groupRepo.clear()
        claimMappers.clear()
        idps.clear()
        tenantKeys.clear()
        themes.clear()
        portalConfigs.clear()
        attrs.clear()
        auditRepo.clear()
        auditPort.clear()
        apiKeyRepo.clear()

        tenantRepo.add(master)
        tenantRepo.add(acme)
        appRepo.add(acmeApp)
        userRepo.add(acmeUser)

        fullScopeKey = createMasterKey(listOf(ApiScope.TENANTS_EXPORT, ApiScope.TENANTS_IMPORT))
        exportOnlyKey = createMasterKey(listOf(ApiScope.TENANTS_EXPORT))
        noScopeKey = createMasterKey(listOf(ApiScope.USERS_READ))
        nonMasterKey =
            (
                apiKeyService.create(
                    tenantId = acme.id,
                    name = "non-master",
                    scopes = listOf(ApiScope.TENANTS_EXPORT, ApiScope.TENANTS_IMPORT),
                ) as ApiKeyResult.Success
            ).value.rawKey
    }

    private fun createMasterKey(scopes: List<String>): String =
        (apiKeyService.create(tenantId = master.id, name = "test", scopes = scopes) as ApiKeyResult.Success)
            .value.rawKey

    @Test
    fun `export returns 401 without a bearer token`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/acme/export") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `export returns 401 when bearer token belongs to non-master tenant`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/acme/export") {
                    bearerAuth(nonMasterKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `export returns 403 when key lacks tenants_export scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/acme/export") {
                    bearerAuth(noScopeKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `export returns 422 when passphrase is too weak`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/acme/export") {
                    bearerAuth(exportOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"short"}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `export returns 404 when tenant does not exist`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/missing/export") {
                    bearerAuth(exportOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `export records ADMIN_TENANT_EXPORTED audit event on success`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/acme/export") {
                    bearerAuth(exportOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(auditPort.hasEvent(AuditEventType.ADMIN_TENANT_EXPORTED))
        }

    @Test
    fun `import returns 403 when key lacks tenants_import scope`() =
        testApplication {
            application { installTestApp() }
            val response =
                client.post("/admin/api/v1/tenants/import") {
                    bearerAuth(exportOnlyKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"envelope":"bkp1.aaaa.bbbb.cccc","passphrase":"this-is-a-strong-passphrase","newSlug":"acme-staging"}""",
                    )
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `import returns 422 on wrong passphrase`() =
        testApplication {
            application { installTestApp() }
            val envelope = exportEnvelope(client = this, key = fullScopeKey, slug = "acme")
            val response =
                client.post("/admin/api/v1/tenants/import") {
                    bearerAuth(fullScopeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            kotlinx.serialization.json.JsonObject
                                .serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("envelope", kotlinx.serialization.json.JsonPrimitive(envelope))
                                put("passphrase", kotlinx.serialization.json.JsonPrimitive("the-wrong-passphrase-here"))
                                put("newSlug", kotlinx.serialization.json.JsonPrimitive("acme-staging"))
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `import returns 409 when destination slug already exists`() =
        testApplication {
            application { installTestApp() }
            val envelope = exportEnvelope(client = this, key = fullScopeKey, slug = "acme")
            val response =
                client.post("/admin/api/v1/tenants/import") {
                    bearerAuth(fullScopeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            kotlinx.serialization.json.JsonObject
                                .serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("envelope", kotlinx.serialization.json.JsonPrimitive(envelope))
                                put(
                                    "passphrase",
                                    kotlinx.serialization.json.JsonPrimitive("this-is-a-strong-passphrase"),
                                )
                                put("newSlug", kotlinx.serialization.json.JsonPrimitive("acme"))
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `export then import round-trip creates a new tenant`() =
        testApplication {
            application { installTestApp() }
            val envelope = exportEnvelope(client = this, key = fullScopeKey, slug = "acme")
            val response =
                client.post("/admin/api/v1/tenants/import") {
                    bearerAuth(fullScopeKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            kotlinx.serialization.json.JsonObject
                                .serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("envelope", kotlinx.serialization.json.JsonPrimitive(envelope))
                                put(
                                    "passphrase",
                                    kotlinx.serialization.json.JsonPrimitive("this-is-a-strong-passphrase"),
                                )
                                put("newSlug", kotlinx.serialization.json.JsonPrimitive("acme-staging"))
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val newTenant = tenantRepo.findBySlug("acme-staging")
            assertNotNull(newTenant, "imported tenant must exist on the destination side")
            assertTrue(auditPort.hasEvent(AuditEventType.ADMIN_TENANT_IMPORTED))
        }

    private suspend fun exportEnvelope(
        client: io.ktor.server.testing.ApplicationTestBuilder,
        key: String,
        slug: String,
    ): String {
        val response =
            client.client.post("/admin/api/v1/tenants/$slug/export") {
                bearerAuth(key)
                contentType(ContentType.Application.Json)
                setBody("""{"passphrase":"this-is-a-strong-passphrase"}""")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val obj = Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
        return (obj["envelope"] as kotlinx.serialization.json.JsonPrimitive).content
    }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            bearer("api-key") {
                realm = "KotAuth REST API"
                authenticate { credential ->
                    if (credential.token.startsWith("kauth_")) ApiKeyPrincipal(rawToken = credential.token) else null
                }
            }
        }
        routing {
            adminBackupRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenantRepo,
                backupExporterService =
                    BackupExporterService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = appRepo,
                        roleRepository = roleRepo,
                        groupRepository = groupRepo,
                        claimMapperRepository = claimMappers,
                        identityProviderRepository = idps,
                        tenantKeyRepository = tenantKeys,
                        userAttributeRepository = attrs,
                        auditLogRepository = auditRepo,
                    ),
                backupImporterService =
                    BackupImporterService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = appRepo,
                        roleRepository = roleRepo,
                        groupRepository = groupRepo,
                        claimMapperRepository = claimMappers,
                        identityProviderRepository = idps,
                        tenantKeyRepository = tenantKeys,
                        themeRepository = themes,
                        portalConfigRepository = portalConfigs,
                        userAttributeRepository = attrs,
                        emailBrandingRepository = FakeTenantEmailBrandingRepository(),
                        auditLogPort = auditPort,
                        transactionRunner = txRunner,
                    ),
                backupEncryptionPort = crypto,
                auditLogPort = auditPort,
                currentSchemaVersion = 38,
                kotauthVersion = "test",
            )
        }
    }
}
