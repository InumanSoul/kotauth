package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserAttribute
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.UserAttributeService
import com.kauth.domain.service.UserSelfServiceService
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
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the admin console routes that manage user attributes
 * and tenant claim mappers.
 */
class AdminUserAttributesAndClaimMappersTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val userAttributeRepo = FakeUserAttributeRepository()
    private val claimMapperRepo = FakeTenantClaimMapperRepository()
    private val hasher = FakePasswordHasher()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key-32-chars-plus-more")

    private val userAttributeService =
        UserAttributeService(
            userAttributeRepository = userAttributeRepo,
            userRepository = userRepo,
        )
    private val claimMapperService =
        CachingClaimMapperService(mapperRepository = claimMapperRepo)

    private val masterTenant =
        Tenant(id = TenantId(1), slug = "master", displayName = "Master", issuerUrl = null, theme = TenantTheme.DEFAULT)
    private val acme =
        Tenant(id = TenantId(2), slug = "acme", displayName = "Acme", issuerUrl = null, theme = TenantTheme.DEFAULT)

    private val adminUser =
        User(
            id = UserId(1),
            tenantId = TenantId(1),
            username = "admin",
            email = "admin@kauth",
            fullName = "Admin",
            passwordHash = hasher.hash("admin"),
        )
    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(2),
            username = "alice",
            email = "alice@acme",
            fullName = "Alice",
            passwordHash = hasher.hash("pw"),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        appRepo.clear()
        sessionRepo.clear()
        roleRepo.clear()
        groupRepo.clear()
        auditLogRepo.clear()
        auditLogPort.clear()
        userAttributeRepo.clear()
        claimMapperRepo.clear()

        tenantRepo.add(masterTenant)
        tenantRepo.add(acme)
        userRepo.add(adminUser)
        userRepo.add(alice)

        val adminRole =
            roleRepo.save(
                com.kauth.domain.model.Role(
                    id = null,
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = com.kauth.domain.model.RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    // =========================================================================
    // User attributes admin UI
    // =========================================================================

    @Test
    fun `GET user detail renders attributes section`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(2), "plan", "trial", Instant.now()))

            val response = authed.get("/admin/workspaces/acme/users/10")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Custom Attributes"))
            assertTrue(body.contains("plan"))
            assertTrue(body.contains("trial"))
        }

    @Test
    fun `GET user detail shows 'Configure mapping' link for unmapped attributes`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(2), "plan", "trial", Instant.now()))

            val response = authed.get("/admin/workspaces/acme/users/10")
            assertTrue(response.bodyAsText().contains("Configure mapping"))
        }

    @Test
    fun `GET user detail shows claim badge for mapped attributes`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(2), "plan", "trial", Instant.now()))
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(2), "plan", "custom:plan"))

            val response = authed.get("/admin/workspaces/acme/users/10")
            assertTrue(response.bodyAsText().contains("→ custom:plan"))
        }

    @Test
    fun `POST attributes creates a new attribute and redirects back`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/10/attributes",
                    formParameters =
                        Parameters.build {
                            append("key", "plan")
                            append("value", "trial")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"] ?: ""
            assertTrue(location.contains("/admin/workspaces/acme/users/10"))
            assertEquals("trial", userAttributeRepo.findAll(UserId(10), TenantId(2))["plan"])
        }

    @Test
    fun `POST attribute delete removes it`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            userAttributeRepo.upsert(UserAttribute(UserId(10), TenantId(2), "plan", "trial", Instant.now()))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/10/attributes/plan/delete",
                    formParameters = Parameters.build {},
                )
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(userAttributeRepo.findAll(UserId(10), TenantId(2)).isEmpty())
        }

    // =========================================================================
    // Claim mappers admin UI
    // =========================================================================

    @Test
    fun `GET claim-mappers list renders empty state`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/claim-mappers")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("No claim mappers configured"))
        }

    @Test
    fun `GET claim-mappers list renders configured mappers`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            claimMapperRepo.upsert(TenantClaimMapper(TenantId(2), "plan", "custom:plan"))

            val response = authed.get("/admin/workspaces/acme/settings/claim-mappers")
            val body = response.bodyAsText()
            assertTrue(body.contains("plan"))
            assertTrue(body.contains("custom:plan"))
        }

    @Test
    fun `POST claim-mappers creates a new mapper`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/claim-mappers",
                    formParameters =
                        Parameters.build {
                            append("attributeKey", "plan")
                            append("claimName", "custom:plan")
                            append("includeInAccess", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = claimMapperRepo.findAll(TenantId(2)).single()
            assertEquals("plan", stored.attributeKey)
            assertEquals("custom:plan", stored.claimName)
            assertTrue(stored.includeInAccess)
        }

    @Test
    fun `POST claim-mappers returns 422 for reserved claim name`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/claim-mappers",
                    formParameters =
                        Parameters.build {
                            append("attributeKey", "my_sub")
                            append("claimName", "sub")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("reserved"))
        }

    @Test
    fun `POST claim-mapper delete removes it`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            claimMapperRepo.upsert(TenantClaimMapper(TenantId(2), "plan", "custom:plan"))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/claim-mappers/plan/delete",
                    formParameters = Parameters.build {},
                )
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(claimMapperRepo.findAll(TenantId(2)).isEmpty())
        }

    // -------------------------------------------------------------------------
    // Shared wiring
    // -------------------------------------------------------------------------

    private fun buildSelfService() =
        UserSelfServiceService(
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            evTokenRepo = FakeEmailVerificationTokenRepository(),
            prTokenRepo = FakePasswordResetTokenRepository(),
            emailPort = FakeEmailPort(),
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun buildAdminService() =
        AdminService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            selfServiceService = buildSelfService(),
        )

    private fun buildAdminUserService() =
        com.kauth.domain.service.AdminUserService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            selfServiceService = buildSelfService(),
        )

    private fun buildRoleGroupService() =
        RoleGroupService(
            roleRepository = roleRepo,
            groupRepository = groupRepo,
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            auditLog = auditLogPort,
        )

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/test-admin-login",
            formParameters = Parameters.build {},
        )
    }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-admin-login") {
                call.sessions.set(
                    AdminSession(
                        userId = 1,
                        tenantId = 1,
                        username = "admin",
                        accessToken = "",
                        idToken = "",
                        adminSessionId = null,
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            adminRoutes(
                adminService = buildAdminService(),
                workspaceSettingsService =
                    com.kauth.domain.service
                        .WorkspaceSettingsService(tenantRepo, auditLogPort),
                adminUserService = buildAdminUserService(),
                roleGroupService = buildRoleGroupService(),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                userAttributeService = userAttributeService,
                claimMapperService = claimMapperService,
            )
        }
    }
}
