package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.SecurityMethodsService
import com.kauth.domain.service.WorkspaceSettingsService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for admin workspace settings routes:
 * general settings, SMTP, security policy, branding.
 *
 * All routes live behind the session guard — tests login first,
 * then exercise the settings endpoints with a cookie jar.
 */
class AdminSettingsTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val idpRepo = FakeIdentityProviderRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val tokenPort = FakeTokenPort()
    private val themeRepo = FakeThemeRepository()
    private val emailBrandingRepo = FakeTenantEmailBrandingRepository()

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
        )

    private fun buildAppMgmtService() =
        com.kauth.domain.service.ApplicationManagementService(
            applicationRepository = appRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
        )

    private fun buildWorkspaceSettingsService() =
        WorkspaceSettingsService(
            tenantRepository = tenantRepo,
            auditLog = auditLogPort,
            themeRepository = themeRepo,
            emailBrandingRepository = emailBrandingRepo,
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

    private fun buildSecurityMethodsService() =
        SecurityMethodsService(
            tenantRepository = tenantRepo,
            identityProviderRepository = idpRepo,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        idpRepo.clear()
        auditLogPort.clear()
        themeRepo.clear()
        emailBrandingRepo.clear()
        tokenPort.reset()
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
        roleRepo.assignRoleToUser(
            UserId(1),
            adminRole.id!!,
        )
    }

    // =========================================================================
    // General workspace settings
    // =========================================================================

    @Test
    fun `GET workspace settings returns 200 for authenticated admin`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST workspace settings saves and redirects with saved flag`() =
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
                    url = "/admin/workspaces/acme/settings",
                    formParameters =
                        Parameters.build {
                            append("displayName", "Acme Updated")
                            append("tokenExpirySeconds", "7200")
                            append("refreshTokenExpirySeconds", "172800")
                            append("registrationEnabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("saved=true") == true)
        }

    // =========================================================================
    // SMTP settings
    // =========================================================================

    @Test
    fun `GET smtp settings returns 200 for authenticated admin`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/smtp")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST smtp settings saves and redirects with saved flag`() =
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
                    url = "/admin/workspaces/acme/settings/smtp",
                    formParameters =
                        Parameters.build {
                            append("smtpHost", "smtp.example.com")
                            append("smtpPort", "587")
                            append("smtpFromAddress", "no-reply@acme.dev")
                            append("smtpTlsEnabled", "true")
                            append("smtpEnabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("saved=true") == true)
        }

    // =========================================================================
    // Security policy settings
    // =========================================================================

    @Test
    fun `GET security settings returns 200 for authenticated admin`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/security")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST security settings saves password policy and redirects`() =
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
                    url = "/admin/workspaces/acme/settings/security",
                    formParameters =
                        Parameters.build {
                            append("passwordPolicyMinLength", "12")
                            append("passwordPolicyRequireSpecial", "true")
                            append("passwordPolicyRequireUppercase", "true")
                            append("passwordPolicyRequireNumber", "true")
                            append("mfaPolicy", "required")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(response.headers["Location"]?.contains("saved=true") == true)
        }

    // =========================================================================
    // Auth methods grid POST (now at /settings/sign-in-methods)
    // =========================================================================

    @Test
    fun `POST sign-in-methods saves method grid and redirects with saved=methods`() =
        testApplication {
            application { installTestAppWithSecurityMethods() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/sign-in-methods",
                    formParameters =
                        Parameters.build {
                            append("enabled_password", "on")
                            append("enabled_passkey", "on")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertContains(response.headers["Location"] ?: "", "saved=methods")

            val persisted = tenantRepo.findBySlug("acme")!!
            assertTrue(persisted.securityConfig.passwordLoginEnabled)
            assertTrue(persisted.passkeysEnabled)
        }

    @Test
    fun `POST sign-in-methods silently drops toggles on non-toggleable rows`() =
        testApplication {
            application { installTestAppWithSecurityMethods() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // enabled_social_google is submitted but the workspace has no Google IDP credentials,
            // so its row is non-toggleable — the handler must silently drop it, not error out.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/sign-in-methods",
                    formParameters =
                        Parameters.build {
                            append("enabled_password", "on")
                            append("enabled_social_google", "on")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertContains(response.headers["Location"] ?: "", "saved=methods")
        }

    @Test
    fun `POST sign-in-methods rejects with NoMethodsEnabled when all methods off`() =
        testApplication {
            application { installTestAppWithSecurityMethods() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // No enabled_* params — every toggleable method maps to false.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/sign-in-methods",
                    formParameters = Parameters.build {},
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "NoMethodsEnabled")
        }

    @Test
    fun `POST sign-in-methods rejects with SmtpRequired when password disabled without SMTP`() =
        testApplication {
            application { installTestAppWithSecurityMethods() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // Only passkey on, password off — workspace has no SMTP.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/sign-in-methods",
                    formParameters =
                        Parameters.build {
                            append("enabled_passkey", "on")
                        },
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "SmtpRequired")
        }

    // =========================================================================
    // Branding settings
    // =========================================================================

    @Test
    fun `GET branding settings returns 200 for authenticated admin`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/branding")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `GET branding renders Default Locale section with auto-detect option`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/branding").bodyAsText()

            assertTrue(body.contains("themeDefaultLocale"), "Locale select input missing")
            assertTrue(body.contains("Auto-detect"), "Auto-detect option missing")
        }

    @Test
    fun `POST branding silently drops unknown locale not in availableLocales`() =
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
                    url = "/admin/workspaces/acme/settings/branding",
                    formParameters =
                        Parameters.build {
                            append("themeDefaultLocale", "es")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val saved = themeRepo.findByTenantId(workspace.id)?.defaultLocale
            assertEquals(null, saved, "Unknown locale must not be persisted")
        }

    @Test
    fun `POST branding persists locale when present in availableLocales`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/branding",
                formParameters =
                    Parameters.build {
                        append("themeDefaultLocale", "en")
                    },
            )

            val saved = themeRepo.findByTenantId(workspace.id)?.defaultLocale
            assertEquals("en", saved)
        }

    @Test
    fun `branding POST does not consume fromDisplayName (UI-dropped, API-only)`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val before = emailBrandingRepo.findByTenantId(TenantId(2))?.fromDisplayName

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/branding",
                formParameters =
                    Parameters.build {
                        append("emailSupportEmail", "support@example.com")
                        append("emailFromDisplayName", "Should Not Be Persisted")
                    },
            )

            val after = emailBrandingRepo.findByTenantId(TenantId(2))
            assertEquals(before, after?.fromDisplayName, "fromDisplayName must NOT be writable via branding form")
            assertEquals("support@example.com", after?.supportEmail)
        }

    @Test
    fun `branding page renders two top-level cards Brand Identity and Visual Theme`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val html = authed.get("/admin/workspaces/acme/settings/branding").bodyAsText()

            assertContains(html, "Brand Identity")
            assertContains(html, "Visual Theme")
            assertContains(html, "name=\"themeLogoUrl\"")
            assertContains(html, "name=\"themeAccentColor\"")
            assertContains(html, "name=\"emailSupportEmail\"")
            assertFalse(
                html.contains("name=\"emailFromDisplayName\""),
                "fromDisplayName input must not appear in the branding form",
            )
        }

    @Test
    fun `POST workspace settings for unknown slug returns 404`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/ghost/settings")

            assertEquals(HttpStatusCode.NotFound, response.status)
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

    private fun io.ktor.server.application.Application.installTestAppWithSecurityMethods() {
        installTestApp(securityMethodsService = buildSecurityMethodsService())
    }

    private fun io.ktor.server.application.Application.installTestApp(
        securityMethodsService: SecurityMethodsService? = null,
    ) {
        install(ContentNegotiation) { json() }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            // Test-only route to inject an admin session without bypass
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
                workspaceSettingsService = buildWorkspaceSettingsService(),
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
                roleRepository = roleRepo,
                userAttributeService =
                    com.kauth.domain.service.UserAttributeService(
                        userAttributeRepository = com.kauth.fakes.FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    com.kauth.infrastructure.CachingClaimMapperService(
                        mapperRepository = com.kauth.fakes.FakeTenantClaimMapperRepository(),
                    ),
                securityMethodsService = securityMethodsService,
            )
        }
    }
}
