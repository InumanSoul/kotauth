package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.domain.service.AdminAccountService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.RoleGroupService
import com.kauth.domain.service.WebAuthnService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.CachingClaimMapperService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.KeyProvisioningService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
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
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminUserPasskeyRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val appRepo = FakeApplicationRepository()
    private val sessionRepo = FakeSessionRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val auditLogRepo = FakeAuditLogRepository()
    private val auditLogPort = FakeAuditLogPort()
    private val hasher = FakePasswordHasher()
    private val credentialRepo = FakeWebAuthnCredentialRepository()
    private val mfaRepo = FakeMfaRepository()
    private val relyingParty = FakeRelyingPartyAdapter()

    private val keyProvisioningService = mockk<KeyProvisioningService>(relaxed = true)
    private val encryptionService = EncryptionService("test-secret-key-32-chars-plus-more")

    private val masterTenant =
        Tenant(
            id = TenantId(1),
            slug = "master",
            displayName = "Master",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    private val acmeTenant =
        Tenant(
            id = TenantId(2),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            passkeysEnabled = true,
        )

    private val adminUser =
        User(
            id = UserId(1),
            tenantId = TenantId(1),
            username = "admin",
            email = "admin@kauth",
            fullName = "Admin",
            passwordHash = hasher.hash("admin"),
            enabled = true,
        )

    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(2),
            username = "alice",
            email = "alice@acme",
            fullName = "Alice",
            passwordHash = hasher.hash("pw"),
            enabled = true,
            mfaEnabled = true,
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

    private fun buildRoleGroupService() =
        RoleGroupService(
            roleRepository = roleRepo,
            groupRepository = groupRepo,
            tenantRepository = tenantRepo,
            userRepository = userRepo,
            applicationRepository = appRepo,
            auditLog = auditLogPort,
        )

    private fun buildWebAuthnService() =
        WebAuthnService(
            credentialRepository = credentialRepo,
            relyingParty = relyingParty,
            secretKey = "test-secret-key-32chars-long-xxxx",
            auditLog = auditLogPort,
            userRepository = userRepo,
        )

    private fun buildMfaService() =
        MfaService(
            mfaRepository = mfaRepo,
            userRepository = userRepo,
            tenantRepository = tenantRepo,
            passwordHasher = hasher,
            auditLog = auditLogPort,
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
        credentialRepo.clear()
        mfaRepo.clear()

        tenantRepo.add(masterTenant)
        tenantRepo.add(acmeTenant)
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

    private fun seedPasskey(userId: UserId = UserId(10)): WebAuthnCredential =
        credentialRepo.save(
            WebAuthnCredential(
                id = null,
                userId = userId,
                tenantId = TenantId(2),
                credentialId = "cred-${UUID.randomUUID()}",
                publicKeyCose = ByteArray(32) { it.toByte() },
                signCounter = 0L,
                aaguid = null,
                transports = emptyList(),
                name = "Alice's Passkey",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
            ),
        )

    // =========================================================================
    // POST /users/{userId}/passkeys/{credId}/revoke
    // =========================================================================

    @Test
    fun `POST admin users passkey revoke removes credential and emits audit`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            val cred = seedPasskey()
            val credId = cred.id!!

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/10/passkeys/$credId/revoke",
                    formParameters = Parameters.build {},
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                credentialRepo.findByUserId(UserId(10), TenantId(2)).isEmpty(),
                "Credential should be deleted after revoke",
            )
            assertTrue(
                auditLogPort.hasEvent(AuditEventType.PASSKEY_ADMIN_REVOKED),
                "PASSKEY_ADMIN_REVOKED audit event should be emitted",
            )
        }

    // =========================================================================
    // POST /users/{userId}/passkeys/reset-all
    // =========================================================================

    @Test
    fun `POST admin users passkey reset-all removes all credentials and emits audit`() =
        testApplication {
            application { installTestApp() }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            seedPasskey()
            seedPasskey()

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/10/passkeys/reset-all",
                    formParameters = Parameters.build {},
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                credentialRepo.findByUserId(UserId(10), TenantId(2)).isEmpty(),
                "All credentials should be deleted after reset-all",
            )
            assertTrue(
                auditLogPort.hasEvent(AuditEventType.PASSKEY_ADMIN_RESET_ALL),
                "PASSKEY_ADMIN_RESET_ALL audit event should be emitted",
            )
        }

    // =========================================================================
    // POST /users/{userId}/mfa/reset
    // =========================================================================

    @Test
    fun `POST admin users mfa reset disables MFA and emits audit`() =
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
                    url = "/admin/workspaces/acme/users/10/mfa/reset",
                    formParameters = Parameters.build {},
                )

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                auditLogPort.hasEvent(AuditEventType.MFA_ADMIN_RESET),
                "MFA_ADMIN_RESET audit event should be emitted",
            )
            // MfaService.disableMfa also emits MFA_DISABLED
            assertTrue(
                auditLogPort.hasEvent(AuditEventType.MFA_DISABLED),
                "MFA_DISABLED audit event should be emitted by MfaService.disableMfa",
            )
        }

    // =========================================================================
    // POST /users/{userId}/passkeys/reset-all — SMTP gate
    // =========================================================================

    @Test
    fun `POST admin users passkey reset-all blocked when passwordLoginDisabled and no SMTP`() =
        testApplication {
            application { installTestApp(passwordLoginDisabledNoSmtp = true) }
            val authed =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }
            login(authed)

            seedPasskey()

            val response =
                authed.submitForm(
                    url = "/admin/workspaces/acme/users/10/passkeys/reset-all",
                    formParameters = Parameters.build {},
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("OperatorLockoutBlocked"),
                "Response must contain OperatorLockoutBlocked error message",
            )
        }

    // ─── Shared Wiring ─────────────────────────────────────────────────────

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(
            url = "/test-admin-login",
            formParameters = Parameters.build {},
        )
    }

    private fun io.ktor.server.application.Application.installTestApp(passwordLoginDisabledNoSmtp: Boolean = false) {
        if (passwordLoginDisabledNoSmtp) {
            tenantRepo.clear()
            tenantRepo.add(masterTenant)
            tenantRepo.add(
                acmeTenant.copy(
                    passwordLoginDisabled = true,
                    smtpEnabled = false,
                ),
            )
            userRepo.clear()
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
                        userAttributeRepository = FakeUserAttributeRepository(),
                        userRepository = userRepo,
                    ),
                claimMapperService =
                    CachingClaimMapperService(
                        mapperRepository = FakeTenantClaimMapperRepository(),
                    ),
                webAuthnService = buildWebAuthnService(),
                mfaService = buildMfaService(),
                auditLogPort = auditLogPort,
            )
        }
    }
}
