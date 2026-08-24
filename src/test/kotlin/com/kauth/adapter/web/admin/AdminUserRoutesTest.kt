package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.RoleGroupService
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
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.HttpClient
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * User detail's IdP-managed indicator and the editable SCIM name parts. The indicator is rendered
 * from `externalId` alone, so its assertions are about what an operator reads before editing a
 * field a sync owns; the name-part assertions pin that `fullName` stays the display name.
 */
class AdminUserRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val apiKeyRepo = FakeApiKeyRepository()
    private val hasher = FakePasswordHasher()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private val masterTenant =
        Tenant(
            id = TenantId(1),
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val workspace =
        Tenant(
            id = TenantId(2),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val adminUser =
        User(
            id = UserId(1),
            tenantId = TenantId(1),
            username = "admin",
            email = "admin@kotauth.dev",
            fullName = "Admin",
            passwordHash = hasher.hash("admin-pass"),
            enabled = true,
        )

    private val roleGroupService =
        RoleGroupService(
            roleRepository = roleRepo,
            groupRepository = groupRepo,
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            auditLog = auditLogPort,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        groupRepo.clear()
        apiKeyRepo.clear()
        auditLogPort.clear()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
        val adminRole = roleRepo.add(Role(tenantId = TenantId(1), name = "admin", scope = RoleScope.TENANT))
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    @Test
    fun `a provisioned user shows the IdP-managed badge and its externalId`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada", externalId = "idp-7")

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertTrue(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
            assertTrue(body.contains("idp-7"), "the correlation key must be readable, not just implied")
            // The warning is the point of the badge: without it an operator edits a name, the next
            // sync reverts it, and nothing on screen explains why.
            assertTrue(body.contains(EnglishStrings.SCIM_IDP_MANAGED_MAY_BE_OVERWRITTEN))
        }

    @Test
    fun `a locally created user shows no badge`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("bob")

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_MAY_BE_OVERWRITTEN))
        }

    @Test
    fun `editing a user persists both name parts without touching the display name`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada")

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/${user.id!!.value}/edit",
                    formParameters =
                        Parameters.build {
                            append("email", "ada@example.com")
                            append("fullName", "Ada Lovelace")
                            append("givenName", "Ada")
                            append("familyName", "Lovelace")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = userRepo.findById(user.id!!, workspace.id)!!
            assertEquals("Ada", stored.givenName)
            assertEquals("Lovelace", stored.familyName)
            assertEquals("Ada Lovelace", stored.fullName)
        }

    @Test
    fun `editing a profile preserves the externalId the identity provider set`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada", externalId = "idp-ada-1")

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/${user.id!!.value}/edit",
                    formParameters =
                        Parameters.build {
                            append("email", "ada@example.com")
                            append("fullName", "Ada Lovelace")
                            append("givenName", "Ada")
                            append("familyName", "Lovelace")
                        },
                )

            // The form has no externalId field and replaceUserProfile treats every parameter as
            // authoritative, so passing null there would unlink the user from its IdP under a
            // success redirect, with nothing on screen saying it happened.
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("idp-ada-1", userRepo.findById(user.id!!, workspace.id)!!.externalId)
        }

    @Test
    fun `clearing a name part stores null rather than an empty string`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada", givenName = "Ada", familyName = "Lovelace")

            authed.submitForm(
                url = "/admin/workspaces/acme/users/${user.id!!.value}/edit",
                formParameters =
                    Parameters.build {
                        append("email", "ada@example.com")
                        append("fullName", "Ada Lovelace")
                        append("givenName", "")
                        append("familyName", "")
                    },
            )

            val stored = userRepo.findById(user.id!!, workspace.id)!!
            // An empty string in the column would round-trip back out through SCIM as a real value.
            assertNull(stored.givenName)
            assertNull(stored.familyName)
        }

    @Test
    fun `creating a user persists the name parts alongside the display name`() =
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
                    url = "/admin/workspaces/acme/users",
                    formParameters =
                        Parameters.build {
                            append("username", "ada")
                            append("email", "ada@example.com")
                            append("fullName", "Ada Lovelace")
                            append("givenName", "Ada")
                            append("familyName", "Lovelace")
                            append("setupMode", "password")
                            append("password", "correct-horse-battery")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = userRepo.findByUsername(workspace.id, "ada")!!
            assertEquals("Ada", stored.givenName)
            assertEquals("Lovelace", stored.familyName)
            assertEquals("Ada Lovelace", stored.fullName)
        }

    @Test
    fun `the edit form offers both name parts`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada", givenName = "Ada", familyName = "Lovelace")

            val body =
                authed
                    .get("/admin/workspaces/acme/users/${user.id!!.value}/edit-fragment")
                    .bodyAsText()

            assertTrue(body.contains("""name="givenName""""))
            assertTrue(body.contains("""name="familyName""""))
            assertTrue(body.contains(EnglishStrings.USER_NAME_PARTS_HINT))
        }

    private fun addWorkspaceUser(
        username: String,
        externalId: String? = null,
        givenName: String? = null,
        familyName: String? = null,
    ): User =
        userRepo.add(
            User(
                tenantId = workspace.id,
                username = username,
                email = "$username@acme.test",
                fullName = username,
                passwordHash = hasher.hash("pw"),
                enabled = true,
                externalId = externalId,
                givenName = givenName,
                familyName = familyName,
            ),
        )

    private suspend fun login(client: HttpClient) {
        client.submitForm(url = "/test-admin-login", formParameters = Parameters.build { })
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
                call.sessions.set(AdminSession(userId = 1, tenantId = 1, username = "admin"))
                call.respond(HttpStatusCode.OK, "session set")
            }
            adminRoutes(
                accountService =
                    AdminAccountService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        auditLog = auditLogPort,
                        credentialFlowService = credentialFlowService(),
                    ),
                workspaceSettingsService =
                    com.kauth.domain.service
                        .WorkspaceSettingsService(tenantRepo, auditLogPort),
                adminUserService =
                    com.kauth.domain.service.AdminUserService(
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        sessionRepository = sessionRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                        credentialFlowService = credentialFlowService(),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = appRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                    ),
                roleGroupService = roleGroupService,
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                apiKeyService = ApiKeyService(apiKeyRepository = apiKeyRepo, tenantRepository = tenantRepo),
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    CachingClaimMapperService(mapperRepository = FakeTenantClaimMapperRepository()),
            )
        }
    }

    private fun credentialFlowService() =
        CredentialFlowService(
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
}
