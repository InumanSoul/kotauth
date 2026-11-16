package com.kauth.adapter.web.admin

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.ApiKeyResult
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WorkspaceCreationService
import com.kauth.domain.service.WorkspaceSettingsService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.KeyProvisioningService
import com.kauth.infrastructure.PortalClientProvisioning
import com.kauth.infrastructure.TenantProvisioner
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdminTenantRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val appRepo = FakeApplicationRepository()
    private val roleRepo = FakeRoleRepository()
    private val tenantKeys = FakeTenantKeyRepository()
    private val audit = FakeAuditLogPort()
    private val apiKeyService = ApiKeyService(apiKeyRepository = FakeApiKeyRepository(), tenantRepository = tenantRepo)

    private val master = Tenant(TenantId(1), "master", "Master", issuerUrl = null, theme = TenantTheme.DEFAULT)
    private val acme = Tenant(TenantId(2), "acme", "Acme", issuerUrl = null, theme = TenantTheme.DEFAULT)

    private var writeKey = ""
    private var importOnlyKey = ""
    private var nonMasterKey = ""

    @BeforeTest
    fun setup() {
        tenantRepo.add(master)
        tenantRepo.add(acme)
        writeKey = key(master.id, listOf(ApiScope.TENANTS_WRITE))
        importOnlyKey = key(master.id, listOf(ApiScope.TENANTS_IMPORT))
        nonMasterKey = key(acme.id, listOf(ApiScope.TENANTS_WRITE))
    }

    private fun key(
        tenantId: TenantId,
        scopes: List<String>,
    ): String {
        val created = apiKeyService.create(tenantId = tenantId, name = "k-${scopes.joinToString()}", scopes = scopes)
        return (created as ApiKeyResult.Success).value.rawKey
    }

    private fun ApplicationTestBuilder.installApp() {
        val workspaceSettings = WorkspaceSettingsService(tenantRepo, audit)
        val roleGroups =
            RoleGroupService(roleRepo, FakeGroupRepository(), tenantRepo, FakeUserRepository(), appRepo, audit)
        val creation =
            WorkspaceCreationService(
                workspaceSettingsService = workspaceSettings,
                roleGroupService = roleGroups,
                tenantProvisioning =
                    TenantProvisioner(
                        KeyProvisioningService(tenantRepo, tenantKeys),
                        PortalClientProvisioning(tenantRepo, appRepo, baseUrl = "https://auth.example"),
                    ),
                transactionRunner = FakeTransactionRunner.passThrough(),
            )
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("api-key") {
                    authenticate { c ->
                        if (c.token.startsWith("kauth_")) ApiKeyPrincipal(rawToken = c.token) else null
                    }
                }
            }
            routing { adminTenantRoutes(apiKeyService, tenantRepo, creation) }
        }
    }

    private suspend fun ApplicationTestBuilder.createTenant(
        body: String,
        bearer: String? = writeKey,
    ): HttpResponse =
        client.post("/admin/api/v1/tenants") {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `a master key with tenants write creates a usable workspace`() =
        testApplication {
            installApp()

            val response =
                createTenant(
                    """{"slug":"orders","displayName":"Orders","registrationEnabled":false,""" +
                        """"emailVerificationRequired":true}""",
                )

            assertEquals(HttpStatusCode.Created, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("orders", body["slug"]!!.jsonPrimitive.content)
            assertFalse(body["registrationEnabled"]!!.jsonPrimitive.boolean)
            val created = assertNotNull(tenantRepo.findBySlug("orders"))
            assertNotNull(tenantKeys.findActiveKey(created.id), "must sign tokens without a restart")
            assertNotNull(appRepo.findByClientId(created.id, PortalClientProvisioning.PORTAL_CLIENT_ID))
            assertEquals(setOf("admin", "user"), roleRepo.findByTenantId(created.id).map { it.name }.toSet())
        }

    @Test
    fun `an existing slug answers 409`() =
        testApplication {
            installApp()
            assertEquals(HttpStatusCode.Conflict, createTenant("""{"slug":"acme","displayName":"Acme"}""").status)
        }

    @Test
    fun `an invalid slug answers 422`() =
        testApplication {
            installApp()
            val response = createTenant("""{"slug":"Not Valid!","displayName":"X"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `a body without the required fields answers 400`() =
        testApplication {
            installApp()
            assertEquals(HttpStatusCode.BadRequest, createTenant("""{"displayName":"X"}""").status)
        }

    @Test
    fun `a key without tenants write answers 403 and creates nothing`() =
        testApplication {
            installApp()
            val response = createTenant("""{"slug":"orders","displayName":"Orders"}""", bearer = importOnlyKey)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(tenantRepo.findBySlug("orders"))
        }

    @Test
    fun `a workspace key cannot create workspaces even with the scope`() =
        testApplication {
            installApp()
            val response = createTenant("""{"slug":"orders","displayName":"Orders"}""", bearer = nonMasterKey)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNull(tenantRepo.findBySlug("orders"))
        }

    @Test
    fun `no bearer token answers 401`() =
        testApplication {
            installApp()
            assertEquals(
                HttpStatusCode.Unauthorized,
                createTenant("""{"slug":"orders","displayName":"Orders"}""", bearer = null).status,
            )
        }
}
