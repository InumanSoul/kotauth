package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.fakes.FakeApiKeyRepository
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.ApiKeyPrincipal
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [apiRoutes].
 *
 * Spins up Ktor's in-memory test engine with the real routing, real auth
 * intercept, and real scope guard — wired to fakes for all persistence.
 *
 * Focus areas:
 *   1. API key authentication guard (missing, invalid, wrong tenant, expired, revoked)
 *   2. Scope enforcement (missing scope → 403)
 *   3. User CRUD through the API (list, get, create)
 *   4. Cross-tenant isolation
 *   5. Audit log query endpoint
 */
class ApiRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()

    private val tenant = Tenant(
        id = 1,
        slug = "acme",
        displayName = "Acme Corp",
        issuerUrl = null,
        theme = TenantTheme.DEFAULT,
    )

    private val otherTenant = Tenant(
        id = 50,
        slug = "other",
        displayName = "Other Corp",
        issuerUrl = null,
        theme = TenantTheme.DEFAULT,
    )

    private val user = User(
        id = 10,
        tenantId = 1,
        username = "alice",
        email = "alice@example.com",
        fullName = "Alice Smith",
        passwordHash = hasher.hash("password123"),
        enabled = true,
    )

    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()

    private val apiKeyService = ApiKeyService(
        apiKeyRepository = apiKeyRepo,
        tenantRepository = tenantRepo,
    )

    private val selfServiceService = UserSelfServiceService(
        userRepository = userRepo,
        tenantRepository = tenantRepo,
        sessionRepository = sessionRepo,
        passwordHasher = hasher,
        auditLog = auditLogPort,
        evTokenRepo = evTokenRepo,
        prTokenRepo = prTokenRepo,
        emailPort = emailPort,
    )

    private val adminService = AdminService(
        tenantRepository = tenantRepo,
        userRepository = userRepo,
        applicationRepository = appRepo,
        passwordHasher = hasher,
        auditLog = auditLogPort,
        sessionRepository = sessionRepo,
        selfServiceService = selfServiceService,
    )

    private val roleGroupService = RoleGroupService(
        roleRepository = roleRepo,
        groupRepository = groupRepo,
        tenantRepository = tenantRepo,
        userRepository = userRepo,
        applicationRepository = appRepo,
        auditLog = auditLogPort,
    )

    private var rawApiKey: String = ""

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        groupRepo.clear()
        appRepo.clear()
        sessionRepo.clear()
        apiKeyRepo.clear()
        auditLogRepo.clear()
        auditLogPort.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()

        tenantRepo.add(tenant)
        tenantRepo.add(otherTenant)
        userRepo.add(user)

        val created = apiKeyService.create(
            tenantId = 1,
            name = "Test Key",
            scopes = ApiScope.ALL,
        )
        rawApiKey = (created as com.kauth.domain.service.ApiKeyResult.Success).value.rawKey
    }

    // =========================================================================
    // Auth guard — missing / invalid / wrong-tenant / expired / revoked key
    // =========================================================================

    @Test
    fun `GET users returns 401 when no Bearer token is provided`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/acme/api/v1/users")

        // Ktor's bearer challenge fires before the route intercept — body is empty
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET users returns 401 when Bearer token does not start with kauth_`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth("not-a-valid-prefix-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET users returns 401 when Bearer token is unknown`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth("kauth_acme_thiskeyDoesNotExistInTheRepo0000000")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Invalid API key"))
    }

    @Test
    fun `GET users returns 401 when API key belongs to a different tenant`() = testApplication {
        application { installTestApp() }

        val otherKey = apiKeyService.create(
            tenantId = 50,
            name = "Other Tenant Key",
            scopes = ApiScope.ALL,
        )
        val otherRawKey = (otherKey as com.kauth.domain.service.ApiKeyResult.Success).value.rawKey

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth(otherRawKey)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET users returns 401 when API key is revoked`() = testApplication {
        application { installTestApp() }

        val revokedResult = apiKeyService.create(
            tenantId = 1,
            name = "Revoked Key",
            scopes = ApiScope.ALL,
        )
        val revokedKey = (revokedResult as com.kauth.domain.service.ApiKeyResult.Success).value
        apiKeyService.revoke(revokedKey.apiKey.id!!, tenantId = 1)

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth(revokedKey.rawKey)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET users returns 404 for unknown tenant slug`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/ghost/api/v1/users") {
            bearerAuth(rawApiKey)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Tenant not found"))
    }

    // =========================================================================
    // Scope enforcement
    // =========================================================================

    @Test
    fun `GET users returns 403 when API key lacks users_read scope`() = testApplication {
        application { installTestApp() }

        val limitedResult = apiKeyService.create(
            tenantId = 1,
            name = "Roles Only Key",
            scopes = listOf(ApiScope.ROLES_READ),
        )
        val limitedRawKey = (limitedResult as com.kauth.domain.service.ApiKeyResult.Success).value.rawKey

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth(limitedRawKey)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Insufficient scope"))
    }

    // =========================================================================
    // User CRUD
    // =========================================================================

    @Test
    fun `GET users returns 200 with user list for valid API key`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/acme/api/v1/users") {
            bearerAuth(rawApiKey)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"data\""), "Response must contain data envelope")
        assertTrue(body.contains("alice"), "Response must include alice user")
    }

    @Test
    fun `GET users by id returns 200 with user details`() = testApplication {
        application { installTestApp() }

        val response = client.get("/t/acme/api/v1/users/10") {
            bearerAuth(rawApiKey)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("alice"))
        assertTrue(body.contains("alice@example.com"))
    }

    @Test
    fun `GET users by id returns 404 for user in different tenant`() = testApplication {
        application { installTestApp() }

        userRepo.add(
            User(
                id = 20,
                tenantId = 50,
                username = "bob",
                email = "bob@other.com",
                fullName = "Bob",
                passwordHash = hasher.hash("pass"),
                enabled = true,
            ),
        )

        val response = client.get("/t/acme/api/v1/users/20") {
            bearerAuth(rawApiKey)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST users creates a new user and returns 201`() = testApplication {
        application { installTestApp() }

        val response = client.post("/t/acme/api/v1/users") {
            bearerAuth(rawApiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"charlie","email":"charlie@example.com","fullName":"Charlie Brown","password":"StrongP@ss1"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("charlie"))
    }

    // =========================================================================
    // Audit log query
    // =========================================================================

    @Test
    fun `GET audit-logs returns events for the tenant`() = testApplication {
        application { installTestApp() }

        auditLogRepo.add(
            AuditEvent(
                tenantId = 1,
                userId = 10,
                clientId = null,
                eventType = AuditEventType.LOGIN_SUCCESS,
                ipAddress = "127.0.0.1",
                userAgent = "test",
            ),
        )

        val limitedResult = apiKeyService.create(
            tenantId = 1,
            name = "Audit Key",
            scopes = listOf(ApiScope.AUDIT_LOGS_READ),
        )
        val auditRawKey = (limitedResult as com.kauth.domain.service.ApiKeyResult.Success).value.rawKey

        val response = client.get("/t/acme/api/v1/audit-logs") {
            bearerAuth(auditRawKey)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("LOGIN_SUCCESS"))
    }

    // =========================================================================
    // Test application wiring
    // =========================================================================

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            bearer("api-key") {
                realm = "KotAuth REST API"
                authenticate { tokenCredential ->
                    if (tokenCredential.token.startsWith("kauth_")) {
                        ApiKeyPrincipal(rawToken = tokenCredential.token)
                    } else {
                        null
                    }
                }
            }
        }
        routing {
            apiRoutes(
                apiKeyService = apiKeyService,
                tenantRepository = tenantRepo,
                userRepository = userRepo,
                roleRepository = roleRepo,
                groupRepository = groupRepo,
                applicationRepository = appRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                roleGroupService = roleGroupService,
                adminService = adminService,
            )
        }
    }
}
