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
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.ResourceServerService
import com.kauth.domain.service.RoleGroupService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlin.test.assertTrue

class AdminApplicationRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val tokenPort = FakeTokenPort()
    private val fakeResourceServerRepo = FakeResourceServerRepository()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key")

    private val tenantId = TenantId(2)
    private val tenantSlug = "acme"

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
            id = tenantId,
            slug = tenantSlug,
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

    private fun buildCredentialFlowService() =
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

    private fun buildAdminService() =
        AdminAccountService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            auditLog = auditLogPort,
            credentialFlowService = buildCredentialFlowService(),
        )

    private fun buildAdminUserService() =
        com.kauth.domain.service.AdminUserService(
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            sessionRepository = sessionRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
            credentialFlowService = buildCredentialFlowService(),
            collisionCheck =
                com.kauth.domain.service
                    .IdentifierCollisionCheck(userRepo),
            usernameGenerator =
                com.kauth.domain.service
                    .UsernameGenerator(userRepo),
        )

    private fun buildAppMgmtService() =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
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

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        fakeResourceServerRepo.clear()
        auditLogPort.clear()
        tokenPort.reset()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
        val adminRole =
            roleRepo.add(
                Role(
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    // =========================================================================
    // POST /applications — create
    // =========================================================================

    @Test
    fun `a new client secret is shown once and never travels in the URL`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/applications",
                    formParameters =
                        Parameters.build {
                            append("clientId", "erp-caller")
                            append("name", "ERP Caller")
                            append("accessType", "confidential")
                            append("grantTypes", "client_credentials")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            val app = appRepo.findByClientId(tenantId, "erp-caller")!!
            val secret = appRepo.findClientSecretHash(app.id)!!.removePrefix("hashed:")
            assertFalse(location.contains("?"), "Got: $location")

            assertTrue(authed.get(location).bodyAsText().contains(secret))
            assertFalse(authed.get(location).bodyAsText().contains(secret), "A reload must not show it again")
        }

    @Test
    fun `a temporary password link is shown once and never travels in the URL`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            val user =
                userRepo.add(
                    com.kauth.domain.model.User(
                        tenantId = tenantId,
                        username = "temp-user",
                        email = "temp-user@acme.test",
                        fullName = "Temp User",
                        passwordHash = "hashed:old",
                    ),
                )

            val response = authed.post("/admin/workspaces/$tenantSlug/users/${user.id!!.value}/set-temporary-password")

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]!!
            assertFalse(location.contains("token") || location.contains("flash"), "Got: $location")
            assertTrue(authed.get(location).bodyAsText().contains("/t/$tenantSlug/change-password?token="))
            assertFalse(authed.get(location).bodyAsText().contains("/t/$tenantSlug/change-password?token="))
        }

    @Test
    fun `the workspace page confirms a backup import`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/$tenantSlug?saved=imported&rewrites=0").bodyAsText()

            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.TOAST_BACKUP_IMPORTED))
        }

    @Test
    fun `the import confirmation says how many users got a new sub`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/$tenantSlug?saved=imported&rewrites=0&newsubjects=3").bodyAsText()

            assertTrue(
                body.contains("3 users got a new sub"),
                "the operator must learn that relying parties must relink",
            )
        }

    @Test
    fun `creating a confidential application with client credentials in one submit succeeds`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/applications",
                    formParameters =
                        Parameters.build {
                            append("clientId", "m2m-caller")
                            append("name", "M2M Caller")
                            append("accessType", "confidential")
                            append("grantTypes", "client_credentials")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = appRepo.findByClientId(tenantId, "m2m-caller")
            assertEquals(
                setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                stored?.grantTypes,
            )
        }

    @Test
    fun `creating a public application does not flash a secret`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/applications",
                    formParameters =
                        Parameters.build {
                            append("clientId", "spa-client")
                            append("name", "SPA Client")
                            append("accessType", "public")
                            append("redirectUris", "https://app.example.com/callback")
                            append("grantTypes", "authorization_code")
                            append("grantTypes", "refresh_token")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .none { it.startsWith("KOTAUTH_ADMIN_FLASH=") },
            )

            val stored = appRepo.findByClientId(tenantId, "spa-client")
            assertEquals(
                setOf(
                    com.kauth.domain.model.GrantType.AUTHORIZATION_CODE,
                    com.kauth.domain.model.GrantType.REFRESH_TOKEN,
                ),
                stored?.grantTypes,
            )
        }

    @Test
    fun `the create form constrains client id to the server regex`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body =
                authed.get("/admin/workspaces/$tenantSlug/applications/new").bodyAsText()

            assertTrue(body.contains("""pattern="[a-z0-9-]+""""))
        }

    @Test
    fun `the create form does not disable the client credentials checkbox`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body =
                authed.get("/admin/workspaces/$tenantSlug/applications/new").bodyAsText()

            val checkbox =
                Regex("""<input[^>]*value="client_credentials"[^>]*>""").find(body)?.value
            assertTrue(checkbox != null, "client_credentials checkbox not found in create form")
            assertFalse(checkbox.contains("disabled"))
        }

    // =========================================================================
    // POST /applications/{clientId}/edit — update grants
    // =========================================================================

    @Test
    fun `editing an application updates its grant types`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val created =
                appRepo.create(
                    tenantId = tenantId,
                    clientId = "erp-caller",
                    name = "ERP Caller",
                    description = null,
                    accessType = "confidential",
                    redirectUris = emptyList(),
                    grantTypes = setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                    clientSecretHash = hasher.hash("secret"),
                    audience = null,
                )

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/applications/${created.clientId}/edit",
                    formParameters =
                        Parameters.build {
                            append("name", "ERP Caller")
                            append("accessType", "confidential")
                            append("grantTypes", "client_credentials")
                            append("grantTypes", "refresh_token")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = appRepo.findByClientId(tenantId, "erp-caller")
            assertEquals(
                setOf(
                    com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS,
                    com.kauth.domain.model.GrantType.REFRESH_TOKEN,
                ),
                stored?.grantTypes,
            )
        }

    @Test
    fun `the edit form sets a token lifetime, keeps it when absent, and clears it when blank`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            appRepo.create(
                tenantId = tenantId,
                clientId = "ttl-worker",
                name = "TTL Worker",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                clientSecretHash = hasher.hash("secret"),
                audience = null,
            )

            suspend fun submit(lifetime: String?) =
                authed.submitForm(
                    url = "/admin/workspaces/$tenantSlug/applications/ttl-worker/edit",
                    formParameters =
                        Parameters.build {
                            append("name", "TTL Worker")
                            append("accessType", "confidential")
                            append("grantTypes", "client_credentials")
                            lifetime?.let { append("tokenExpiryOverride", it) }
                        },
                )

            assertEquals(HttpStatusCode.Found, submit("900").status)
            assertEquals(900, appRepo.findByClientId(tenantId, "ttl-worker")?.tokenExpiryOverride)

            assertEquals(HttpStatusCode.Found, submit(null).status)
            assertEquals(900, appRepo.findByClientId(tenantId, "ttl-worker")?.tokenExpiryOverride)

            assertEquals(HttpStatusCode.UnprocessableEntity, submit("ten").status)
            assertEquals(900, appRepo.findByClientId(tenantId, "ttl-worker")?.tokenExpiryOverride)

            assertEquals(HttpStatusCode.Found, submit("").status)
            assertEquals(null, appRepo.findByClientId(tenantId, "ttl-worker")?.tokenExpiryOverride)
        }

    @Test
    fun `the edit page shows the workspace lifetime a blank token lifetime falls back to`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            appRepo.create(
                tenantId = tenantId,
                clientId = "ttl-page",
                name = "TTL Page",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                clientSecretHash = hasher.hash("secret"),
                audience = null,
            )
            val workspaceSeconds = tenantRepo.findById(tenantId)!!.tokenExpirySeconds.toString()

            val html = authed.get("/admin/workspaces/$tenantSlug/applications/ttl-page/edit").bodyAsText()

            assertTrue("name=\"tokenExpiryOverride\"" in html)
            assertTrue("placeholder=\"$workspaceSeconds\"" in html)
        }

    // =========================================================================
    // GET /applications/{clientId} — Authorized APIs card
    // =========================================================================

    @Test
    fun `the application detail page links to the authorized APIs page`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val created =
                appRepo.create(
                    tenantId = tenantId,
                    clientId = "erp-caller",
                    name = "ERP Caller",
                    description = null,
                    accessType = "confidential",
                    redirectUris = emptyList(),
                    grantTypes = setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                    clientSecretHash = hasher.hash("secret"),
                    audience = null,
                )

            val body =
                authed
                    .get("/admin/workspaces/$tenantSlug/applications/${created.clientId}")
                    .bodyAsText()

            assertTrue(body.contains("/admin/workspaces/$tenantSlug/applications/${created.clientId}/authorized-apis"))
            assertTrue(body.contains(EnglishStrings.AUTHORIZED_APIS_CARD_TITLE))
            assertTrue(body.contains(EnglishStrings.AUTHORIZED_APIS_CARD_EMPTY_TITLE))
            assertTrue(body.contains(EnglishStrings.AUTHORIZED_APIS_CARD_EMPTY))
            // Confirms the "code" icon resolved to real markup rather than inlineSvgIcon
            // silently rendering nothing for an unknown icon name.
            assertTrue(body.contains("""aria-label="${EnglishStrings.AUTHORIZED_APIS_CARD_EMPTY_TITLE}""""))
        }

    @Test
    fun `the application detail page lists the APIs the application is authorized for`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val created =
                appRepo.create(
                    tenantId = tenantId,
                    clientId = "erp-caller",
                    name = "ERP Caller",
                    description = null,
                    accessType = "confidential",
                    redirectUris = emptyList(),
                    grantTypes = setOf(com.kauth.domain.model.GrantType.CLIENT_CREDENTIALS),
                    clientSecretHash = hasher.hash("secret"),
                    audience = null,
                )
            val resourceServer =
                fakeResourceServerRepo.seed(
                    com.kauth.domain.model.ResourceServer(
                        tenantId = tenantId,
                        identifier = "https://api.acme.example.com",
                        name = "ERP API",
                    ),
                )
            fakeResourceServerRepo.registerClient(created.id, tenantId)
            fakeResourceServerRepo.setAuthorizedResources(created.id, listOf(resourceServer.id!!))

            val body =
                authed
                    .get("/admin/workspaces/$tenantSlug/applications/${created.clientId}")
                    .bodyAsText()

            assertTrue(body.contains("ERP API"))
            assertTrue(body.contains("https://api.acme.example.com"))
            assertFalse(body.contains(EnglishStrings.AUTHORIZED_APIS_CARD_EMPTY))
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/test-admin-login",
            formParameters = Parameters.build { },
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
                    ),
                )
                call.respond(HttpStatusCode.OK, "session set")
            }
            adminRoutes(
                accountService = buildAdminService(),
                workspaceSettingsService =
                    com.kauth.domain.service
                        .WorkspaceSettingsService(tenantRepo, auditLogPort),
                adminUserService = buildAdminUserService(),
                applicationManagementService = buildAppMgmtService(),
                roleGroupService = buildRoleGroupService(),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                encryptionService = encryptionService,
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
                resourceServerService = ResourceServerService(fakeResourceServerRepo),
            )
        }
    }
}
