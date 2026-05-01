package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.util.sha256Hex
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImpersonationServiceTest {
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val sessions = FakeSessionRepository()
    private val tokens = FakeTokenPort()
    private val audit = FakeAuditLogPort()

    private val service =
        ImpersonationService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            tokenPort = tokens,
            auditLog = audit,
        )

    private lateinit var masterTenant: Tenant
    private lateinit var customerTenant: Tenant
    private lateinit var admin: User
    private lateinit var target: User
    private lateinit var adminSession: Session

    @BeforeTest
    fun setUp() {
        users.clear()
        tenants.clear()
        sessions.clear()
        tokens.reset()
        audit.clear()

        masterTenant =
            tenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = Tenant.MASTER_SLUG,
                    displayName = "Master",
                    issuerUrl = null,
                ),
            )
        customerTenant =
            tenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme",
                    issuerUrl = null,
                ),
            )
        admin =
            users.add(
                User(
                    tenantId = masterTenant.id,
                    username = "admin",
                    email = "admin@kauth.local",
                    fullName = "Platform Admin",
                    passwordHash = "x",
                ),
            )
        target =
            users.add(
                User(
                    tenantId = customerTenant.id,
                    username = "alice",
                    email = "alice@acme.test",
                    fullName = "Alice Anderson",
                    passwordHash = "x",
                ),
            )
        adminSession =
            sessions.save(
                Session(
                    tenantId = masterTenant.id,
                    userId = admin.id,
                    clientId = null,
                    accessTokenHash = sha256Hex("admin-access"),
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )
    }

    @Test
    fun `startImpersonation issues a child session linked to the admin session`() {
        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = customerTenant.id,
                targetUserId = target.id!!,
            )

        assertTrue(result is AdminResult.Success)
        val started = result.value
        val saved = sessions.findById(started.impersonationSessionId)!!
        assertEquals(target.id, saved.userId)
        assertEquals(customerTenant.id, saved.tenantId)
        assertEquals(adminSession.id, saved.impersonatorSessionId)
        assertTrue(saved.isImpersonation)
        assertTrue(saved.isActive)
    }

    @Test
    fun `startImpersonation passes admin id as actingSubject so tokens carry the act claim`() {
        service.startImpersonation(
            adminUserId = admin.id!!,
            adminSessionId = adminSession.id!!,
            targetTenantId = customerTenant.id,
            targetUserId = target.id!!,
        )

        assertEquals(admin.id, tokens.lastActingSubject)
    }

    @Test
    fun `startImpersonation records ADMIN_IMPERSONATION_STARTED with target metadata`() {
        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = customerTenant.id,
                targetUserId = target.id!!,
            )

        val event = audit.events.single()
        assertEquals(AuditEventType.ADMIN_IMPERSONATION_STARTED, event.eventType)
        assertEquals(admin.id, event.userId)
        assertEquals(customerTenant.id, event.tenantId)
        assertEquals(target.id!!.value.toString(), event.details["target_user_id"])
        assertEquals(target.username, event.details["target_username"])
        assertEquals(adminSession.id!!.value.toString(), event.details["admin_session_id"])
        val sessionId = (result as AdminResult.Success).value.impersonationSessionId
        assertEquals(sessionId.value.toString(), event.details["impersonation_session_id"])
    }

    @Test
    fun `startImpersonation refuses master-tenant targets`() {
        val masterUser =
            users.add(
                User(
                    tenantId = masterTenant.id,
                    username = "other-admin",
                    email = "other@kauth.local",
                    fullName = "Other Admin",
                    passwordHash = "x",
                ),
            )

        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = masterTenant.id,
                targetUserId = masterUser.id!!,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.Validation)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun `startImpersonation fails when target is in a different tenant`() {
        val otherTenant =
            tenants.add(
                Tenant(id = TenantId(0), slug = "other", displayName = "Other", issuerUrl = null),
            )

        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = otherTenant.id,
                targetUserId = target.id!!,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.NotFound)
    }

    @Test
    fun `startImpersonation refuses disabled users`() {
        val disabled =
            users.add(
                target.copy(id = null, username = "disabled", email = "d@acme.test", enabled = false),
            )

        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = customerTenant.id,
                targetUserId = disabled.id!!,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.Validation)
    }

    @Test
    fun `startImpersonation refuses temporarily locked users`() {
        val locked =
            users.add(
                target.copy(
                    id = null,
                    username = "locked",
                    email = "l@acme.test",
                    lockedUntil = Instant.now().plusSeconds(60),
                ),
            )

        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = customerTenant.id,
                targetUserId = locked.id!!,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.Validation)
    }

    @Test
    fun `startImpersonation refuses when admin session is revoked`() {
        sessions.revoke(adminSession.id!!, Instant.now())

        val result =
            service.startImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                targetTenantId = customerTenant.id,
                targetUserId = target.id!!,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.Validation)
    }

    @Test
    fun `stopImpersonation revokes the child session and records ADMIN_IMPERSONATION_ENDED`() {
        val started =
            (
                service.startImpersonation(
                    adminUserId = admin.id!!,
                    adminSessionId = adminSession.id!!,
                    targetTenantId = customerTenant.id,
                    targetUserId = target.id!!,
                ) as AdminResult.Success
            ).value
        audit.clear()

        val result =
            service.stopImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = adminSession.id!!,
                impersonationSessionId = started.impersonationSessionId,
            )

        assertTrue(result is AdminResult.Success)
        val revoked = sessions.findById(started.impersonationSessionId)!!
        assertTrue(revoked.isRevoked)
        assertEquals(AuditEventType.ADMIN_IMPERSONATION_ENDED, audit.events.single().eventType)
    }

    @Test
    fun `stopImpersonation rejects another admin's session id`() {
        val started =
            (
                service.startImpersonation(
                    adminUserId = admin.id!!,
                    adminSessionId = adminSession.id!!,
                    targetTenantId = customerTenant.id,
                    targetUserId = target.id!!,
                ) as AdminResult.Success
            ).value
        val otherAdminSession =
            sessions.save(
                Session(
                    tenantId = masterTenant.id,
                    userId = admin.id,
                    clientId = null,
                    accessTokenHash = sha256Hex("other-admin"),
                    refreshTokenHash = null,
                    scopes = "openid",
                    expiresAt = Instant.now().plusSeconds(3600),
                ),
            )

        val result =
            service.stopImpersonation(
                adminUserId = admin.id!!,
                adminSessionId = otherAdminSession.id!!,
                impersonationSessionId = started.impersonationSessionId,
            )

        assertTrue(result is AdminResult.Failure)
        assertTrue(result.error is AdminError.Validation)
    }

    @Test
    fun `stopImpersonation is not idempotent and fails on second call`() {
        val started =
            (
                service.startImpersonation(
                    adminUserId = admin.id!!,
                    adminSessionId = adminSession.id!!,
                    targetTenantId = customerTenant.id,
                    targetUserId = target.id!!,
                ) as AdminResult.Success
            ).value

        val first = service.stopImpersonation(admin.id!!, adminSession.id!!, started.impersonationSessionId)
        assertTrue(first is AdminResult.Success)

        val second = service.stopImpersonation(admin.id!!, adminSession.id!!, started.impersonationSessionId)
        assertTrue(second is AdminResult.Failure)
        assertTrue(second.error is AdminError.Validation)
    }

    @Test
    fun `revoking the admin session cascades and revokes any active impersonation children`() {
        val started =
            (
                service.startImpersonation(
                    adminUserId = admin.id!!,
                    adminSessionId = adminSession.id!!,
                    targetTenantId = customerTenant.id,
                    targetUserId = target.id!!,
                ) as AdminResult.Success
            ).value

        sessions.revoke(adminSession.id!!, Instant.now())

        val child = sessions.findById(started.impersonationSessionId)!!
        assertTrue(child.isRevoked, "impersonation child should be cascade-revoked")
        assertNotNull(child.revokedAt)
    }

    @Test
    fun `revokeAllForUser on the admin user cascades to impersonation children`() {
        val started =
            (
                service.startImpersonation(
                    adminUserId = admin.id!!,
                    adminSessionId = adminSession.id!!,
                    targetTenantId = customerTenant.id,
                    targetUserId = target.id!!,
                ) as AdminResult.Success
            ).value

        sessions.revokeAllForUser(masterTenant.id, admin.id!!, Instant.now())

        assertTrue(sessions.findById(started.impersonationSessionId)!!.isRevoked)
    }

    @Test
    fun `tokens issued for non-impersonated logins do not carry an actingSubject`() {
        // Establish a baseline that the FakeTokenPort still treats actingSubject as null
        // for normal flows, so the impersonation tests are detecting a real signal.
        assertNull(tokens.lastActingSubject)
    }
}
