package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.ApiKeyBootstrapService
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
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
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
import kotlin.test.assertTrue

/**
 * Integration tests for the workspace SCIM provisioning page and the API key dialect selector.
 */
class AdminScimRoutesTest {
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

    private val apiKeyService =
        ApiKeyService(
            apiKeyRepository = apiKeyRepo,
            tenantRepository = tenantRepo,
        )

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

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        apiKeyRepo.clear()
        auditLogPort.clear()
        tenantRepo.add(masterTenant)
        tenantRepo.add(workspace)
        userRepo.add(adminUser)
        val adminRole =
            roleRepo.add(
                com.kauth.domain.model.Role(
                    tenantId = TenantId(1),
                    name = "admin",
                    scope = com.kauth.domain.model.RoleScope.TENANT,
                ),
            )
        roleRepo.assignRoleToUser(UserId(1), adminRole.id!!)
    }

    @Test
    fun `the provisioning page shows the endpoint URL and every registered dialect`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/provisioning")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("https://auth.example.com/t/acme/scim/v2"), "endpoint URL must be shown")
            com.kauth.adapter.web.scim.scimDialects.forEach { dialect ->
                assertTrue(body.contains(dialect.label), "the notes must cover the ${dialect.id} dialect")
            }
        }

    @Test
    fun `the provisioning page states that DELETE deactivates rather than deletes`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_DELETE_DEACTIVATES))
        }

    @Test
    fun `the provisioning page states that a group DELETE is permanent`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            // It sits directly under the notice saying a user DELETE is reversible, which reads as
            // a promise about DELETE in general unless the group case is spelled out.
            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_DELETE_GROUP_PERMANENT))
        }

    @Test
    fun `a workspace whose only scim key is revoked is not told it has no such key`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey
            apiKeyService.revoke(key.id!!, TenantId(2))

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            // The table right below the status row shows this key with a Revoked badge.
            assertTrue(body.contains("Directory sync"), "the revoked key is still listed")
            assertFalse(
                body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_STATUS_NO_KEY),
                "the workspace has a scim-scoped key; it is revoked, which is a different sentence",
            )
            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_STATUS_KEYS_REVOKED))
        }

    @Test
    fun `a workspace with no scim key at all is still told so`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_STATUS_NO_KEY))
        }

    @Test
    fun `the provisioning page lists only the keys holding the scim scope`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            apiKeyService.create(TenantId(2), "Reporting job", listOf(ApiScope.USERS_READ))

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertTrue(body.contains("Directory sync"), "a scim-scoped key must be listed")
            assertFalse(body.contains("Reporting job"), "a key without the scim scope must not be listed")
        }

    @Test
    fun `the status row is honest when no provisioning activity has been recorded`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertTrue(body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_STATUS_UNKNOWN))
        }

    @Test
    fun `the create key page offers the dialect selector with the scim scope preselected`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body =
                authed
                    .get("/admin/workspaces/acme/settings/api-keys/new?scope=${ApiScope.SCIM}")
                    .bodyAsText()

            assertTrue(body.contains("name=\"scimDialect\""), "the dialect selector must be rendered")
            com.kauth.adapter.web.scim.scimDialects.forEach { dialect ->
                assertTrue(body.contains("value=\"${dialect.id}\""), "${dialect.id} must be selectable")
            }
        }

    @Test
    fun `creating a key persists the selected dialect`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val selected =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            authed.submitForm(
                url = "/admin/workspaces/acme/settings/api-keys",
                formParameters =
                    Parameters.build {
                        append("name", "Directory sync")
                        append("scopes", ApiScope.SCIM)
                        append("scimDialect", selected)
                    },
            )

            val stored = apiKeyRepo.findByTenantId(TenantId(2)).single()
            assertEquals(selected, stored.scimDialect)
        }

    @Test
    fun `an unregistered dialect is refused on the create form and no key is created`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys",
                    formParameters =
                        Parameters.build {
                            append("name", "Directory sync")
                            append("scopes", ApiScope.SCIM)
                            append("scimDialect", "not-a-dialect")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(
                response.bodyAsText().contains(com.kauth.adapter.web.EnglishStrings.SCIM_DIALECT_UNKNOWN_REFUSAL),
                "the form must say why the submission was refused",
            )
            assertTrue(
                apiKeyRepo.findByTenantId(TenantId(2)).isEmpty(),
                "a refused submission must not create a key under a substituted dialect",
            )
        }

    @Test
    fun `an operator can correct the dialect on an existing key`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey
            val target =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", target) },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("/provisioning") == true)
            assertEquals(target, apiKeyRepo.findById(key.id!!, TenantId(2))!!.scimDialect)

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()
            assertTrue(
                Regex("<option(?=[^>]*value=\"$target\")(?=[^>]*selected)[^>]*>").containsMatchIn(body),
                "the provisioning page must show the corrected dialect as the selected option",
            )
        }

    @Test
    fun `correcting the dialect leaves the rest of the key untouched`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM))
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                formParameters =
                    Parameters.build {
                        append(
                            "scimDialect",
                            com.kauth.adapter.web.scim.scimDialects
                                .last()
                                .id,
                        )
                    },
            )

            val stored = apiKeyRepo.findById(key.id!!, TenantId(2))!!
            assertEquals(key.name, stored.name)
            assertEquals(key.scopes, stored.scopes)
            assertEquals(key.keyPrefix, stored.keyPrefix)
            assertEquals(key.keyHash, stored.keyHash)
            assertEquals(key.enabled, stored.enabled)
            assertEquals(key.expiresAt, stored.expiresAt)
        }

    @Test
    fun `an unregistered dialect is refused on the edit form and the stored dialect is unchanged`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // Created on a non-default dialect, so "unchanged" is distinguishable from "coerced
            // to rfc" — the exact substitution this route must no longer make.
            val existing =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            val created =
                apiKeyService.create(TenantId(2), "Directory sync", listOf(ApiScope.SCIM), scimDialect = existing)
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", "not-a-dialect") },
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains(com.kauth.adapter.web.EnglishStrings.SCIM_DIALECT_UNKNOWN_REFUSAL),
                "the refusal must say why",
            )
            assertEquals(existing, apiKeyRepo.findById(key.id!!, TenantId(2))!!.scimDialect)
        }

    @Test
    fun `a key from another workspace is not found and is not updated`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val created = apiKeyService.create(TenantId(1), "Master sync", listOf(ApiScope.SCIM))
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey
            val target =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", target) },
                )

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(
                ApiKey.DEFAULT_SCIM_DIALECT,
                apiKeyRepo.findById(key.id!!, TenantId(1))!!.scimDialect,
            )
        }

    @Test
    fun `an empty submitted dialect is refused instead of silently resetting the key`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val stored =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            val created = apiKeyService.create(TenantId(2), "Provisioning", listOf(ApiScope.SCIM), scimDialect = stored)
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", "") },
                )

            // "" is a stale or tampered form, not a request for the default; treating it as one
            // downgrades a key to the pass-through under a saved toast.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(stored, apiKeyRepo.findById(key.id!!, TenantId(2))!!.scimDialect)
        }

    @Test
    fun `a submission with no dialect field at all is refused the same way`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val stored =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            val created = apiKeyService.create(TenantId(2), "Provisioning", listOf(ApiScope.SCIM), scimDialect = stored)
            val key = (created as com.kauth.domain.service.ApiKeyResult.Success).value.apiKey

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${key.id}/scim-dialect",
                    formParameters = Parameters.Empty,
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(stored, apiKeyRepo.findById(key.id!!, TenantId(2))!!.scimDialect)
        }

    @Test
    fun `a bootstrapped key gets the dialect its environment names, and the form cannot change it`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // Provisioned the way KAUTH_BOOTSTRAP_API_KEYS provisions it, dialect included — the
            // refusal below is only honest if the environment can actually set one.
            val envDialect =
                com.kauth.adapter.web.scim.scimDialects
                    .last()
                    .id
            ApiKeyBootstrapService(apiKeyRepo, tenantRepo).ensureBootstrapped(
                listOf(
                    ApiKeyBootstrapService.Entry(
                        tenantSlug = "acme",
                        name = "kauth-cli",
                        scopes = listOf(ApiScope.SCIM),
                        keyHash = "hash-v1",
                        scimDialect = envDialect,
                    ),
                ),
            )
            val bootstrapped = apiKeyRepo.findByTenantAndName(TenantId(2), "kauth-cli")!!
            assertEquals(envDialect, bootstrapped.scimDialect)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/api-keys/${bootstrapped.id}/scim-dialect",
                    formParameters = Parameters.build { append("scimDialect", ApiKey.DEFAULT_SCIM_DIALECT) },
                )

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("KAUTH_BOOTSTRAP_API_KEYS"))
            assertEquals(envDialect, apiKeyRepo.findById(bootstrapped.id!!, TenantId(2))!!.scimDialect)
        }

    @Test
    fun `the provisioning page offers no dialect editor for a bootstrapped key`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val bootstrapped =
                apiKeyRepo.save(
                    ApiKey(
                        tenantId = TenantId(2),
                        name = "kauth-cli",
                        keyPrefix = "kauth_acme_boot",
                        keyHash = "hash-v1",
                        scopes = listOf(ApiScope.SCIM),
                        bootstrapName = "kauth-cli",
                    ),
                )

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertTrue(
                body.contains(com.kauth.adapter.web.EnglishStrings.SCIM_DIALECT_ENV_MANAGED),
                "the row must say the dialect follows the environment",
            )
            assertFalse(
                body.contains("/settings/api-keys/${bootstrapped.id}/scim-dialect"),
                "a bootstrapped key must not get an edit form",
            )
        }

    @Test
    fun `the provisioning page is scoped to its own workspace`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            apiKeyService.create(TenantId(1), "Master sync", listOf(ApiScope.SCIM))

            val body = authed.get("/admin/workspaces/acme/provisioning").bodyAsText()

            assertFalse(body.contains("Master sync"), "another workspace's key must never appear")
        }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun login(client: HttpClient) {
        client.submitForm(url = "/test-admin-login", formParameters = Parameters.build { })
    }

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
                        credentialFlowService = buildCredentialFlowService(),
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
                        credentialFlowService = buildCredentialFlowService(),
                        collisionCheck =
                            com.kauth.domain.service
                                .IdentifierCollisionCheck(userRepo),
                        usernameGenerator =
                            com.kauth.domain.service
                                .UsernameGenerator(userRepo),
                    ),
                applicationManagementService =
                    com.kauth.domain.service.ApplicationManagementService(
                        applicationRepository = appRepo,
                        tenantRepository = tenantRepo,
                        passwordHasher = hasher,
                        auditLog = auditLogPort,
                    ),
                roleGroupService =
                    RoleGroupService(
                        roleRepository = roleRepo,
                        groupRepository = groupRepo,
                        tenantRepository = tenantRepo,
                        userRepository = userRepo,
                        applicationRepository = appRepo,
                        auditLog = auditLogPort,
                    ),
                appInfo = AppInfo(),
                tenantRepository = tenantRepo,
                applicationRepository = appRepo,
                userRepository = userRepo,
                sessionRepository = sessionRepo,
                auditLogRepository = auditLogRepo,
                keyProvisioningService = keyProvisioningService,
                apiKeyService = apiKeyService,
                encryptionService = encryptionService,
                roleRepository = roleRepo,
                baseUrl = "https://auth.example.com",
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
            )
        }
    }
}
