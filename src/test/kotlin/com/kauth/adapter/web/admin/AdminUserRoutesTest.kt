package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * User detail's IdP-managed indicator and the editable SCIM name parts.
 *
 * The indicator has two origins behind it: a SCIM `externalId` on the user row, and a just-in-time
 * provisioning event for an account the broker created — which carries no `externalId` at all. The
 * assertions are about what an operator reads before editing a field they may not own; the
 * name-part assertions pin that `fullName` stays the display name.
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
    private val consents = com.kauth.fakes.FakeConsentRepository()
    private val consentService =
        com.kauth.domain.service
            .ConsentService(consents, appRepo, sessionRepo, userRepo, auditLogPort)
    private val socialRepo = com.kauth.fakes.FakeSocialAccountRepository()
    private val idpRepo = com.kauth.fakes.FakeIdentityProviderRepository()
    private val linkedIdentityService =
        com.kauth.domain.service.LinkedIdentityService(
            socialAccounts = socialRepo,
            users = userRepo,
            tenants = tenantRepo,
            identityProviders = idpRepo,
            passkeys = com.kauth.fakes.FakeWebAuthnCredentialRepository(),
            auditLog = auditLogPort,
        )

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
        auditLogRepo.clear()
        socialRepo.clear()
        idpRepo.clear()
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
    fun `an administrator sees what a user allowed an application and can revoke it`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada")
            val partner =
                appRepo.add(
                    com.kauth.domain.model.Application(
                        id =
                            com.kauth.domain.model
                                .ApplicationId(31),
                        tenantId = workspace.id,
                        clientId = "partner-app",
                        name = "Partner Reports",
                        description = null,
                        accessType = com.kauth.domain.model.AccessType.PUBLIC,
                        enabled = true,
                        consentRequired = true,
                    ),
                )
            consents.save(workspace.id, user.id!!, partner.id, setOf("openid", "email"), java.time.Instant.now())

            val page = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()
            assertTrue(page.contains(EnglishStrings.APP_ACCESS_HEADING))
            assertTrue(page.contains("Partner Reports"))
            assertTrue(page.contains("email openid"))

            val revoked = authed.post("/admin/workspaces/acme/users/${user.id!!.value}/app-access/31/revoke")

            assertEquals(HttpStatusCode.Found, revoked.status)
            assertNull(consents.find(workspace.id, user.id!!, partner.id))
            assertEquals(
                com.kauth.domain.model.AuditEventType.ADMIN_CONSENT_REVOKED,
                auditLogPort.events.last().eventType,
            )
            val again = authed.post("/admin/workspaces/acme/users/${user.id!!.value}/app-access/31/revoke")
            assertEquals(HttpStatusCode.NotFound, again.status)
        }

    @Test
    fun `the user page shows the subject tokens carry, not the row id`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada", subject = "3f2b9c1e-7d4a-4e1b-9a8f-0c6d5e4b3a21")

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertTrue(body.contains(EnglishStrings.USER_SUBJECT_LABEL))
            assertTrue(body.contains("data-copy=\"3f2b9c1e-7d4a-4e1b-9a8f-0c6d5e4b3a21\""))
        }

    @Test
    fun `searching the user list by an exact subject finds that user`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            addWorkspaceUser("ada", subject = "sub-ada-0001")
            addWorkspaceUser("bob", subject = "sub-bob-0002")

            val exact = authed.get("/admin/workspaces/acme/users?q=sub-ada-0001").bodyAsText()
            val partial = authed.get("/admin/workspaces/acme/users?q=sub-ada").bodyAsText()

            assertTrue(exact.contains("/users/") && exact.contains(">ada<"))
            assertFalse(exact.contains(">bob<"))
            assertFalse(partial.contains(">ada<"), "a subject matches whole, never as a substring")
        }

    @Test
    fun `an administrator unlinks a user's only identity, after a warning that says so`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("ada")
            idpRepo.seed(workspace.id, "google")
            linkGoogle(user)
            // Password sign-in on, but the account has no password: the link is its only way in.
            userRepo.update(user.copy(passwordHash = User.SENTINEL_PASSWORD_HASH))
            val unlink = "/admin/workspaces/acme/users/${user.id!!.value}/identities/google/unlink"

            val page = authed.get("/admin/workspaces/acme/users/${user.id.value}").bodyAsText()
            assertTrue(page.contains("action=\"$unlink\""))
            assertTrue(page.contains("only way to sign in"))

            val response = authed.post(unlink)

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(socialRepo.findByUserId(user.id).isEmpty())
            val event = auditLogPort.events.single { it.eventType == AuditEventType.ADMIN_IDENTITY_UNLINKED }
            assertEquals(user.id.value.toString(), event.details["targetUser"])
        }

    @Test
    fun `unlinking a user of another workspace changes nothing`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val outsider = userRepo.add(adminUser.copy(id = null, username = "outsider", email = "o@m.test"))
            linkGoogle(outsider)

            val response = authed.post("/admin/workspaces/acme/users/${outsider.id!!.value}/identities/google/unlink")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(1, socialRepo.findByUserId(outsider.id).size)
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
    fun `a user provisioned on first sign-in shows the IdP-managed badge`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("grace")
            recordJitProvisioning(user)

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertTrue(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
            assertTrue(body.contains(EnglishStrings.IDP_MANAGED_BROKERED_ORIGIN))
            // The provider link lives in social_accounts, so there is no externalId to show and no
            // sync that would overwrite an edit made here.
            assertFalse(body.contains(EnglishStrings.SCIM_IDP_EXTERNAL_ID_LABEL))
            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_MAY_BE_OVERWRITTEN))
        }

    @Test
    fun `a provisioning event in another workspace does not badge this user`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("grace")
            // Same user id, another tenant's event: the read has to be scoped, not just filtered by id.
            recordJitProvisioning(user, tenantId = masterTenant.id)

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
        }

    @Test
    fun `another user's provisioning event does not badge this one`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val provisioned = addWorkspaceUser("grace")
            val local = addWorkspaceUser("bob")
            recordJitProvisioning(provisioned)

            val body = authed.get("/admin/workspaces/acme/users/${local.id!!.value}").bodyAsText()

            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
        }

    @Test
    fun `an unrelated event for the same user does not badge them`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            val user = addWorkspaceUser("bob")
            auditLogRepo.add(
                AuditEvent(
                    tenantId = workspace.id,
                    userId = user.id,
                    clientId = null,
                    eventType = AuditEventType.LOGIN_SUCCESS,
                    ipAddress = null,
                    userAgent = null,
                ),
            )

            val body = authed.get("/admin/workspaces/acme/users/${user.id!!.value}").bodyAsText()

            assertFalse(body.contains(EnglishStrings.SCIM_IDP_MANAGED_BADGE))
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
    fun `renaming a user's username succeeds and redirects`() =
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
                            append("username", "ada.lovelace")
                            append("email", "ada@example.com")
                            append("fullName", "Ada Lovelace")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("ada.lovelace", userRepo.findById(user.id!!, workspace.id)!!.username)
        }

    @Test
    fun `renaming a user's username to one already taken does not silently succeed`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            addWorkspaceUser("bob")
            val user = addWorkspaceUser("ada")

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/${user.id!!.value}/edit",
                    formParameters =
                        Parameters.build {
                            append("username", "bob")
                            append("email", "ada@example.com")
                            append("fullName", "Ada Lovelace")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("ada", userRepo.findById(user.id!!, workspace.id)!!.username)
        }

    @Test
    fun `a colliding email in the same submission does not leave the username renamed`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            addWorkspaceUser("bob")
            val user = addWorkspaceUser("ada")

            // Both fields change in one submission and only the email collides — the username
            // rename must not land on its own write ahead of the email check failing.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/${user.id!!.value}/edit",
                    formParameters =
                        Parameters.build {
                            append("username", "ada.lovelace")
                            append("email", "bob@acme.test")
                            append("fullName", "Ada Lovelace")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val stored = userRepo.findById(user.id!!, workspace.id)!!
            assertEquals("ada", stored.username)
            assertEquals("ada@acme.test", stored.email)
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

    private fun recordJitProvisioning(
        user: User,
        tenantId: TenantId = workspace.id,
    ) {
        auditLogRepo.add(
            AuditEvent(
                tenantId = tenantId,
                userId = user.id,
                clientId = null,
                eventType = AuditEventType.JIT_USER_PROVISIONED,
                ipAddress = null,
                userAgent = null,
                details = mapOf("provider" to "oriana"),
            ),
        )
    }

    private fun addWorkspaceUser(
        username: String,
        externalId: String? = null,
        givenName: String? = null,
        familyName: String? = null,
        subject: String? = null,
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
                subject = subject,
            ),
        )

    private fun linkGoogle(user: User) =
        socialRepo.save(
            com.kauth.domain.model.SocialAccount(
                userId = user.id!!,
                tenantId = user.tenantId,
                provider = com.kauth.domain.model.ProviderKey.GOOGLE,
                providerUserId = "g-${user.username}",
                providerEmail = user.email,
                providerName = user.username,
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
                consentService = consentService,
                socialAccountRepository = socialRepo,
                linkedIdentityService = linkedIdentityService,
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
