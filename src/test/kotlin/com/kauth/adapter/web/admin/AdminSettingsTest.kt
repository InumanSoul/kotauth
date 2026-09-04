package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.IdentityProviderProbeService
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
import com.kauth.fakes.FakeOidcDiscoveryPort
import com.kauth.fakes.FakeOidcIssuer
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
import io.ktor.http.HttpHeaders
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

    /** Discovery answers for one issuer; the fake issuer serves the key set that issuer points at. */
    private val fakeIssuer = FakeOidcIssuer()
    private val discoveryPort = FakeOidcDiscoveryPort(issuer = fakeIssuer.issuer)

    private fun buildProbeService() =
        IdentityProviderProbeService(
            discovery = discoveryPort,
            jwks = fakeIssuer,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        userRepo.clear()
        roleRepo.clear()
        idpRepo.clear()
        auditLogPort.clear()
        auditLogRepo.clear()
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
    // Security policy settings — sign-in identifier mode
    // =========================================================================

    @Test
    fun `POST security settings persists sign-in identifier mode EITHER`() =
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
                            append("loginIdentifierMode", "EITHER")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginIdentifierMode.EITHER,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )
        }

    @Test
    fun `POST security settings persists sign-in identifier mode EMAIL`() =
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
                            append("loginIdentifierMode", "EMAIL")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginIdentifierMode.EMAIL,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )
        }

    @Test
    fun `POST security settings with a garbage identifier mode falls back to USERNAME without erroring`() =
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
                            append("loginIdentifierMode", "NONSENSE")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginIdentifierMode.USERNAME,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )
        }

    @Test
    fun `POST security settings omitting the identifier field preserves an EITHER tenant`() =
        testApplication {
            // The field is absent from a partial/programmatic POST here. loginIdentifierMode
            // must fall back to the tenant's persisted value (not to USERNAME) so an operator
            // saving unrelated fields on this form can never silently narrow the sign-in surface.
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/security",
                formParameters =
                    Parameters.build {
                        append("loginIdentifierMode", "EITHER")
                    },
            )
            assertEquals(
                LoginIdentifierMode.EITHER,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/security",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginIdentifierMode.EITHER,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )
        }

    @Test
    fun `POST security settings omitting the identifier field preserves an EMAIL tenant`() =
        testApplication {
            // EMAIL mode performs only findByEmail (UserIdentifierResolver). Resetting this to
            // USERNAME on a field omission would lock out every user who does not know a
            // username, silently, behind an ordinary 302 — this is the case with real lockout
            // consequences, not just a policy relaxation.
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/security",
                formParameters =
                    Parameters.build {
                        append("loginIdentifierMode", "EMAIL")
                    },
            )
            assertEquals(
                LoginIdentifierMode.EMAIL,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/security",
                    formParameters = Parameters.build { },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginIdentifierMode.EMAIL,
                tenantRepo.findBySlug("acme")!!.securityConfig.loginIdentifierMode,
            )
        }

    @Test
    fun `security policy page renders the three sign-in identifier options with the current one checked`() =
        testApplication {
            tenantRepo.update(
                workspace.copy(
                    securityConfig = workspace.securityConfig.copy(loginIdentifierMode = LoginIdentifierMode.EMAIL),
                ),
            )
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/security").bodyAsText()

            val radios =
                Regex("""<input[^>]*name="loginIdentifierMode"[^>]*>""")
                    .findAll(body)
                    .map { it.value }
                    .toList()
            assertEquals(3, radios.size)

            val usernameRadio = radios.first { it.contains("value=\"USERNAME\"") }
            val emailRadio = radios.first { it.contains("value=\"EMAIL\"") }
            val eitherRadio = radios.first { it.contains("value=\"EITHER\"") }

            assertFalse(usernameRadio.contains("checked"))
            assertTrue(emailRadio.contains("checked"))
            assertFalse(eitherRadio.contains("checked"))
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
    fun `POST branding accepts themeLoginLayout=SPLIT and persists it`() =
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
                            append("themeLoginLayout", "SPLIT")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(LoginLayout.SPLIT, themeRepo.findByTenantId(workspace.id)?.loginLayout)
        }

    @Test
    fun `POST branding accepts themeLoginTagline and persists it`() =
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
                            append("themeLoginTagline", "Welcome back to Acme")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("Welcome back to Acme", themeRepo.findByTenantId(workspace.id)?.loginTagline)
        }

    @Test
    fun `POST branding accepts themeLoginBackgroundUrl https and persists it`() =
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
                            append("themeLoginBackgroundUrl", "https://cdn.example.com/hero.jpg")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                "https://cdn.example.com/hero.jpg",
                themeRepo.findByTenantId(workspace.id)?.loginBackgroundUrl,
            )
        }

    @Test
    fun `POST branding returns 422 when loginBackgroundUrl is not http or https`() =
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
                            append("themeLoginBackgroundUrl", "javascript:alert(1)")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(null, themeRepo.findByTenantId(workspace.id)?.loginBackgroundUrl)
        }

    @Test
    fun `POST branding returns 422 when loginTagline exceeds 200 chars`() =
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
                            append("themeLoginTagline", "a".repeat(201))
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `POST branding preserves loginLayout when the field is unknown or malformed`() =
        testApplication {
            application { installTestApp() }
            // Simulate a tenant whose current theme is SPLIT (production keeps Tenant.theme
            // and ThemeRepository in sync via a JOIN; the fakes are independent stores, so
            // both are seeded here to mirror that behavior for this request).
            tenantRepo.add(workspace.copy(theme = TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT)))
            themeRepo.upsert(workspace.id, TenantTheme.DEFAULT.copy(loginLayout = LoginLayout.SPLIT))
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
                            append("themeLoginLayout", "NOT_A_REAL_LAYOUT")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                LoginLayout.SPLIT,
                themeRepo.findByTenantId(workspace.id)?.loginLayout,
                "Unknown loginLayout value must preserve the existing SPLIT setting, not fall back to CENTERED",
            )
        }

    @Test
    fun `branding page renders login layout section with three new fields`() =
        testApplication {
            application { installTestApp() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val html = authed.get("/admin/workspaces/acme/settings/branding").bodyAsText()

            assertContains(html, "name=\"themeLoginLayout\"")
            assertContains(html, "name=\"themeLoginTagline\"")
            assertContains(html, "name=\"themeLoginBackgroundUrl\"")
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
    // Identity provider key guard
    // =========================================================================

    @Test
    fun `saving an identity provider for an unreserved key is accepted and writes the row`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // "oriana" has no compiled-in adapter. Phase 1 refused it here; brokering is the point
            // of Phase 2, so the key is now accepted and the row is written. The issuer is what
            // an unreserved key always needed to broker a login — before Task 5 the form wrote
            // the repository directly and would happily store a row the resolver then refused.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                    formParameters =
                        Parameters.build {
                            append("clientId", "oriana-client-id")
                            append("clientSecret", "oriana-secret")
                            append("issuer", "https://example.oriana.com")
                            append("enabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = idpRepo.findByTenantAndProvider(workspace.id, ProviderKey.of("oriana")!!)
            assertEquals(
                "oriana-client-id",
                stored?.clientId,
                "An unreserved key must persist its own row, not be dropped by a provider guard",
            )
        }

    @Test
    fun `deleting an identity provider for an unreserved key removes its row`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana/delete",
                    formParameters = Parameters.build { },
                )

            // Seeding first is what makes this more than a status check: a guard that refused the
            // key would 400 AND leave the row, so the empty repository is the discriminating half.
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(idpRepo.findAllByTenant(workspace.id).isEmpty())
        }

    @Test
    fun `deleting an identity provider for a reserved key is accepted`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            idpRepo.seed(workspace.id, "google")

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/google/delete",
                    formParameters = Parameters.build { },
                )

            // The companion to the test above: proves the 400 there comes from the key being
            // unreserved, not from something both requests share.
            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(idpRepo.findAllByTenant(workspace.id).isEmpty())
        }

    // =========================================================================
    // Identity providers go through IdentityProviderService
    // =========================================================================

    @Test
    fun `the add form creates an OIDC provider with its issuer and endpoint overrides`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers",
                    formParameters =
                        Parameters.build {
                            append("providerKey", "oriana")
                            append("kind", "oidc")
                            append("clientId", "oriana-client-id")
                            append("clientSecret", "oriana-secret")
                            append("displayName", "Oriana")
                            append("issuer", "https://example.oriana.com")
                            append("jwksUri", "https://example.oriana.com/keys")
                            append("scopes", "openid email")
                            append("enabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            val stored = idpRepo.findByTenantAndProvider(workspace.id, ProviderKey.of("oriana")!!)
            assertEquals(ProviderKind.OIDC, stored?.kind)
            assertEquals("https://example.oriana.com", stored?.issuer)
            assertEquals("https://example.oriana.com/keys", stored?.jwksUri)
            assertEquals("Oriana", stored?.displayName)
            assertEquals("openid email", stored?.scopes)
        }

    @Test
    fun `the admin form refuses an OIDC provider with no issuer and writes nothing`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            // The rule lives in IdentityProviderService, so this only holds while the form
            // writes through it rather than reaching for the repository.
            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                    formParameters =
                        Parameters.build {
                            append("clientId", "oriana-client-id")
                            append("clientSecret", "oriana-secret")
                            append("enabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertContains(response.bodyAsText(), "issuer")
            assertTrue(idpRepo.findAllByTenant(workspace.id).isEmpty())
        }

    @Test
    fun `the admin form refuses a client id it would otherwise have written blank`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/google",
                    formParameters =
                        Parameters.build {
                            append("clientId", "  ")
                            append("clientSecret", "google-secret")
                            append("enabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(idpRepo.findAllByTenant(workspace.id).isEmpty())
        }

    @Test
    fun `the identity providers page never renders a stored client secret`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.add(
                IdentityProvider(
                    tenantId = workspace.id,
                    provider = ProviderKey.of("oriana")!!,
                    clientId = "oriana-client-id",
                    clientSecret = "s3cr3t-oriana-client-secret",
                    kind = ProviderKind.OIDC,
                    issuer = "https://example.oriana.com",
                ),
            )

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            assertFalse("s3cr3t-oriana-client-secret" in body, "The admin page must never render a stored secret")
            assertContains(body, "oriana-client-id")
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

    private fun io.ktor.server.application.Application.installTestAppWithIdpRepo() {
        installTestApp(identityProviderRepository = idpRepo)
    }

    private fun io.ktor.server.application.Application.installTestAppWithoutProbe() {
        installTestApp(identityProviderRepository = idpRepo, identityProviderProbeService = null)
    }

    private fun io.ktor.server.application.Application.installTestApp(
        securityMethodsService: SecurityMethodsService? = null,
        identityProviderRepository: com.kauth.domain.port.IdentityProviderRepository? = null,
        baseUrl: String = APP_BASE_URL,
        identityProviderProbeService: IdentityProviderProbeService? = buildProbeService(),
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
                identityProviderRepository = identityProviderRepository,
                baseUrl = baseUrl,
                identityProviderProbeService = identityProviderProbeService,
            )
        }
    }
    // =========================================================================
    // Identity provider diagnostics — recent sign-in failures
    // =========================================================================

    private fun recordFailure(
        tenantId: TenantId,
        provider: String,
        reason: String,
        emailDomain: String? = null,
        idpErrorCode: String? = null,
    ) = auditLogRepo.add(
        com.kauth.domain.model.AuditEvent(
            tenantId = tenantId,
            userId = null,
            clientId = null,
            eventType = com.kauth.domain.model.AuditEventType.SOCIAL_LOGIN_FAILED,
            ipAddress = "203.0.113.9",
            userAgent = null,
            details =
                buildMap {
                    put("provider", provider)
                    put("reason", reason)
                    emailDomain?.let { put("email_domain", it) }
                    idpErrorCode?.let { put("idp_error_code", it) }
                },
        ),
    )

    @Test
    fun `the identity providers page names the reason a sign-in failed`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")
            recordFailure(workspace.id, "oriana", "domain_not_allowed", emailDomain = "contractor.example")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            // A count of failures is not a diagnosis: the operator fixes an allowlist from the
            // domain and the reason, and nothing else on the page shows either.
            assertContains(body, "Email domain not on the allowed list")
            assertContains(body, "contractor.example")
        }

    @Test
    fun `a redirect URI the provider rejected is visible with its error code`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")
            recordFailure(workspace.id, "oriana", "idp_returned_error", idpErrorCode = "redirect_uri_mismatch")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            // Discovery fetches the issuer's document and cannot see a redirect-URI mismatch.
            // Without this row the operator's experience of that mistake is silence.
            assertContains(body, "redirect_uri_mismatch")
        }

    @Test
    fun `a failure is listed under the provider it happened on and no other`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")
            idpRepo.seed(workspace.id, "google")
            recordFailure(workspace.id, "oriana", "domain_not_allowed", emailDomain = "oriana-side.example")
            recordFailure(workspace.id, "google", "domain_not_allowed", emailDomain = "google-side.example")

            // Each provider has its own page now, so attribution is asserted across two of them:
            // the failure appears on the provider it happened on, and is absent from the other.
            // Counting occurrences on one combined page no longer tests anything.
            val oriana = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()
            val google = authed.get("/admin/workspaces/acme/settings/identity-providers/google").bodyAsText()

            assertContains(oriana, "oriana-side.example")
            assertFalse(
                "google-side.example" in oriana,
                "google's failure must not appear on oriana's page",
            )
            assertContains(google, "google-side.example")
            assertFalse(
                "oriana-side.example" in google,
                "oriana's failure must not appear on google's page",
            )
        }

    @Test
    fun `a failure recorded in another workspace is not listed`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")
            recordFailure(masterTenant.id, "oriana", "domain_not_allowed", emailDomain = "other-workspace.example")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            assertFalse(
                body.contains("other-workspace.example"),
                "Diagnostics are tenant-scoped like every other query, got: $body",
            )
        }

    @Test
    fun `a provider with no recorded failures says so rather than showing nothing`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            assertContains(body, "No sign-in failures recorded for this provider.")
        }
    // =========================================================================
    // Test discovery, the just-in-time toggle, and the allowed-domain chips
    // =========================================================================

    /** The one issuer the fake discovery port answers for. Its endpoints are derived from it. */
    private val probeIssuer get() = fakeIssuer.issuer

    private fun seedOriana(
        jitEnabled: Boolean = false,
        jitAllowedDomains: List<String> = emptyList(),
        clientSecret: String = "oriana-stored-secret",
    ) = idpRepo.add(
        IdentityProvider(
            tenantId = workspace.id,
            provider = ProviderKey.of("oriana")!!,
            clientId = "oriana-client-id",
            clientSecret = clientSecret,
            kind = ProviderKind.OIDC,
            issuer = probeIssuer,
            jitEnabled = jitEnabled,
            jitAllowedDomains = jitAllowedDomains,
        ),
    )

    private fun storedOriana() = idpRepo.findByTenantAndProvider(workspace.id, ProviderKey.of("oriana")!!)

    // =========================================================================
    // The provider catalog and the per-provider pages
    // =========================================================================

    @Test
    fun `the catalog surfaces a broken provider's failure count without a click`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana()
            recordFailure(workspace.id, "oriana", "domain_not_allowed", emailDomain = "contractor.example")
            recordFailure(workspace.id, "oriana", "domain_not_allowed", emailDomain = "contractor.example")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers").bodyAsText()

            // The whole point of collapsing configuration behind a click: an index that hides
            // whether a provider is working is worse than the long form it replaced.
            assertContains(body, EnglishStrings.recentFailures(2))
        }

    @Test
    fun `the catalog reads a healthy provider without a failure badge`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana()

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers").bodyAsText()

            assertFalse("recent failure" in body, "No failures means no alarm, got: $body")
        }

    @Test
    fun `the catalog tells a disabled provider apart from one never configured`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "oriana", enabled = false)

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers").bodyAsText()

            // One grey badge for both states told the operator a configured-but-off provider had
            // never been set up, which is the opposite of the action it needs.
            assertContains(body, EnglishStrings.IDP_STATUS_DISABLED)
            assertFalse(
                EnglishStrings.IDP_STATUS_NOT_CONFIGURED in body,
                "A stored provider has been configured, whatever its switch says",
            )
        }

    @Test
    fun `the catalog never renders a client secret or a credential field`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana(clientSecret = "s3cr3t-catalog-must-not-show")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers").bodyAsText()

            assertFalse("s3cr3t-catalog-must-not-show" in body, "A stored secret never reaches the page")
            assertFalse("name=\"clientSecret\"" in body, "Credentials belong on the provider's own page")
        }

    @Test
    fun `the add page carries a callback URL before a provider key exists`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/new").bodyAsText()

            // Without this the operator registers a guessed redirect URI at the issuer, saves
            // here, then goes back to correct it — the URL is only knowable after it is needed.
            assertContains(body, "$APP_BASE_URL/t/acme/auth/social/provider-key/callback")
            assertContains(body, "data-callback-key-input")
        }

    @Test
    fun `a built-in provider has a page before anything is stored against it`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/identity-providers/google")

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.bodyAsText(), "$APP_BASE_URL/t/acme/auth/social/google/callback")
        }

    @Test
    fun `a brokered key with nothing stored has no page of its own`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val response = authed.get("/admin/workspaces/acme/settings/identity-providers/never-configured")

            // Unlike the reserved keys, an arbitrary key names nothing until it is saved.
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(
                "/admin/workspaces/acme/settings/identity-providers",
                response.headers[HttpHeaders.Location],
            )
        }

    @Test
    fun `the enable switch applies on its own without saving the form`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana()

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/identity-providers/oriana/enabled",
                formParameters = Parameters.build { append("enabled", "false") },
            )

            assertEquals(false, storedOriana()?.enabled, "The switch is its own write, not a form field")
        }

    @Test
    fun `the enable switch leaves the stored secret alone`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana(clientSecret = "kept-through-the-toggle")

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/identity-providers/oriana/enabled",
                formParameters = Parameters.build { append("enabled", "false") },
            )

            // The toggle posts no credentials, so a save that treated a missing secret as an
            // empty one would silently clear it.
            assertEquals("kept-through-the-toggle", storedOriana()?.clientSecret)
        }

    @Test
    fun `test discovery reports the endpoints the issuer publishes`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana()

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                    formParameters = Parameters.build { },
                )
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(body, "$probeIssuer/authorize")
            assertContains(body, "$probeIssuer/token")
            assertContains(body, "$probeIssuer/jwks")
        }

    @Test
    fun `test discovery reports how many signing keys the issuer publishes`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana()

            val body =
                authed
                    .submitForm(
                        url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                        formParameters = Parameters.build { },
                    ).bodyAsText()

            // The fake issuer publishes exactly two usable verification keys. Reaching the key set
            // is the one thing beyond the document itself that discovery genuinely proves.
            assertContains(body, EnglishStrings.IDP_DISCOVERY_KEYS_LABEL)
            assertContains(body, "2")
        }

    @Test
    fun `test discovery leaves the stored provider exactly as it was`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            val before = seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                    formParameters = Parameters.build { },
                )

            // Status and row together: a 404 would also leave the row alone, so the equality
            // assertion only means something once the route is known to have run.
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(before, storedOriana(), "A discovery test is a read — it must write nothing")
            assertEquals(1, idpRepo.findAllByTenant(workspace.id).size)
        }

    @Test
    fun `test discovery names the redirect URI it did not verify`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana()

            val body =
                authed
                    .submitForm(
                        url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                        formParameters = Parameters.build { },
                    ).bodyAsText()

            // A tick that quietly means "half of it is fine" converts uncertainty into false
            // confidence. The panel has to name the half it could not see, and show the URL the
            // operator must register for that half to hold.
            assertContains(body, EnglishStrings.IDP_DISCOVERY_NOT_VERIFIED_TITLE)
            assertContains(body, EnglishStrings.IDP_DISCOVERY_NOT_VERIFIED_REDIRECT)
            assertContains(body, "$APP_BASE_URL/t/acme/auth/social/oriana/callback")
        }

    @Test
    fun `test discovery never renders the stored client secret`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana(clientSecret = "s3cr3t-oriana-probe-secret")

            val body =
                authed
                    .submitForm(
                        url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                        formParameters = Parameters.build { },
                    ).bodyAsText()

            assertFalse("s3cr3t-oriana-probe-secret" in body, "A discovery test must not echo a secret back")
        }

    @Test
    fun `test discovery says why an issuer could not be reached`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.add(
                IdentityProvider(
                    tenantId = workspace.id,
                    provider = ProviderKey.of("oriana")!!,
                    clientId = "oriana-client-id",
                    clientSecret = "oriana-stored-secret",
                    kind = ProviderKind.OIDC,
                    issuer = "https://unreachable.example",
                ),
            )

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana/test-discovery",
                    formParameters = Parameters.build { },
                )
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(body, EnglishStrings.IDP_DISCOVERY_FAILED_TITLE)
            assertContains(body, "https://unreachable.example")
        }

    @Test
    fun `the callback URL on a provider card is the one the login flow sends`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            // The flow builds its redirect_uri from the deployment's base URL. A workspace whose
            // issuerUrl says something else must not change what the operator is told to register,
            // or setup is broken by the page that was meant to guide it.
            tenantRepo.update(workspace.copy(issuerUrl = "https://issuer.acme.example"))
            seedOriana()

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            assertContains(body, "$APP_BASE_URL/t/acme/auth/social/oriana/callback")
            assertFalse(
                "https://issuer.acme.example/t/acme/auth/social/oriana/callback" in body,
                "The callback URL must come from the base URL the login flow uses, not from issuerUrl",
            )
        }

    @Test
    fun `a provider with no allowed domains says no account is created automatically`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana(jitEnabled = true, jitAllowedDomains = emptyList())

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            // Empty means the feature is off. An empty box reads as "not configured yet", which is
            // the opposite meaning, and nothing on the page tells the two apart.
            assertContains(body, EnglishStrings.IDP_JIT_DOMAINS_EMPTY)
        }

    @Test
    fun `the allowed domains a provider already has are rendered as chips`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example", "acme.test"))

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/oriana").bodyAsText()

            assertContains(body, "value=\"acme.example\"")
            assertContains(body, "value=\"acme.test\"")
            assertFalse(EnglishStrings.IDP_JIT_DOMAINS_EMPTY in body, "A populated list is not the off-state")
        }

    @Test
    fun `a built-in provider card carries the just-in-time controls`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed = createClient { install(HttpCookies) }
            login(authed)
            idpRepo.seed(workspace.id, "google")

            val body = authed.get("/admin/workspaces/acme/settings/identity-providers/google").bodyAsText()

            // The gate reads jitEnabled off the row whatever the provider kind is, so a Google
            // workspace needs the same two controls an OIDC one does.
            assertContains(body, "name=\"jitEnabled\"")
            assertContains(body, "name=\"$JIT_DOMAIN_TO_ADD_FIELD\"")
        }

    @Test
    fun `saving switches just-in-time provisioning on for the provider`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana()

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                    formParameters =
                        Parameters.build {
                            append("clientId", "oriana-client-id")
                            append("issuer", probeIssuer)
                            append("kind", "oidc")
                            append("enabled", "true")
                            append("jitEnabled", "true")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(storedOriana()?.jitEnabled == true, "The toggle must reach the stored row")
        }

    @Test
    fun `a domain entered with capitals and spaces is stored normalised`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana(jitEnabled = true)

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                    formParameters =
                        Parameters.build {
                            append("clientId", "oriana-client-id")
                            append("issuer", probeIssuer)
                            append("kind", "oidc")
                            append("enabled", "true")
                            append("jitEnabled", "true")
                            append(JIT_DOMAIN_TO_ADD_FIELD, "  ACME.Example  ")
                        },
                )

            assertEquals(HttpStatusCode.Found, response.status)
            // The service already trims, lower-cases and de-duplicates. What the operator typed and
            // what the chip shows back have to be the same string, so the form must not normalise
            // differently — or not at all.
            assertEquals(listOf("acme.example"), storedOriana()?.jitAllowedDomains)
        }

    @Test
    fun `a domain left ticked survives a save that adds another`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                formParameters =
                    Parameters.build {
                        append("clientId", "oriana-client-id")
                        append("issuer", probeIssuer)
                        append("kind", "oidc")
                        append("enabled", "true")
                        append("jitEnabled", "true")
                        append(JIT_DOMAINS_FIELD, "acme.example")
                        append(JIT_DOMAIN_TO_ADD_FIELD, "acme.test")
                    },
            )

            assertEquals(listOf("acme.example", "acme.test"), storedOriana()?.jitAllowedDomains)
        }

    @Test
    fun `a domain unticked before saving is removed from the list`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example", "contractor.example"))

            authed.submitForm(
                url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                formParameters =
                    Parameters.build {
                        append("clientId", "oriana-client-id")
                        append("issuer", probeIssuer)
                        append("kind", "oidc")
                        append("enabled", "true")
                        append("jitEnabled", "true")
                        append(JIT_DOMAINS_FIELD, "acme.example")
                    },
            )

            // An unticked chip is a removal. Preserving what the row already had would make the
            // control unable to express the one state that matters most — the empty list.
            assertEquals(listOf("acme.example"), storedOriana()?.jitAllowedDomains)
        }

    @Test
    fun `a domain that is not a domain is refused and the stored list is unchanged`() =
        testApplication {
            application { installTestAppWithIdpRepo() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)
            seedOriana(jitEnabled = true, jitAllowedDomains = listOf("acme.example"))

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/settings/identity-providers/oriana",
                    formParameters =
                        Parameters.build {
                            append("clientId", "oriana-client-id")
                            append("issuer", probeIssuer)
                            append("kind", "oidc")
                            append("enabled", "true")
                            append("jitEnabled", "true")
                            append(JIT_DOMAIN_TO_ADD_FIELD, "someone@acme.example")
                        },
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertContains(response.bodyAsText(), "someone@acme.example")
            assertEquals(listOf("acme.example"), storedOriana()?.jitAllowedDomains)
        }

    private companion object {
        /** The deployment base URL the login flow builds its redirect_uri from. */
        const val APP_BASE_URL = "https://kotauth.example"
        const val JIT_DOMAINS_FIELD = "jitAllowedDomains"
        const val JIT_DOMAIN_TO_ADD_FIELD = "jitAllowedDomainToAdd"
    }
}
