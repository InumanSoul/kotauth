package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeConsentRepository
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.AdminClientProvisioning
import com.kauth.infrastructure.PortalClientProvisioning
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentServiceTest {
    private val consents = FakeConsentRepository()
    private val apps = FakeApplicationRepository()
    private val sessions = FakeSessionRepository()
    private val users = FakeUserRepository()
    private val audit = FakeAuditLogPort()
    private val apis = FakeResourceServerRepository()
    private val tenantId = TenantId(1)
    private val alice = UserId(10)
    private val now = Instant.parse("2026-11-25T12:00:00Z")

    private val service =
        ConsentService(consents, apps, sessions, users, audit, apis, Clock.fixed(now, ZoneOffset.UTC))

    private val partnerApp = app(ApplicationId(5), "partner-app", consentRequired = true)
    private val ownApp = app(ApplicationId(6), "own-app", consentRequired = false)

    @BeforeTest
    fun setUp() {
        consents.clear()
        apps.clear()
        sessions.clear()
        users.clear()
        audit.clear()
        apps.add(partnerApp)
        apps.add(ownApp)
        users.add(
            User(
                id = alice,
                tenantId = tenantId,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )
    }

    @Test
    fun `only an application with the switch on asks, and then until the user allows its scopes`() {
        assertFalse(service.isRequired(tenantId, alice, ownApp, "openid profile"))
        assertTrue(service.isRequired(tenantId, alice, partnerApp, "openid profile"))

        service.grant(tenantId, alice, partnerApp, "openid profile", ipAddress = null)

        assertFalse(service.isRequired(tenantId, alice, partnerApp, "profile openid"))
        assertFalse(service.isRequired(tenantId, alice, partnerApp, "openid"), "a narrower request is covered")
        assertTrue(service.isRequired(tenantId, alice, partnerApp, "openid email"), "a new scope asks again")
        assertTrue(service.isRequired(tenantId, alice, partnerApp, "openid", force = true), "prompt=consent asks")
    }

    @Test
    fun `Kotauth's own portal and console never ask, whatever their switch says`() {
        listOf(PortalClientProvisioning.PORTAL_CLIENT_ID, AdminClientProvisioning.ADMIN_CLIENT_ID).forEach { id ->
            val builtIn = partnerApp.copy(clientId = id)
            assertFalse(service.isRequired(tenantId, alice, builtIn, "openid", force = true), id)
        }
    }

    @Test
    fun `a later grant adds to the scopes the user allowed before`() {
        service.grant(tenantId, alice, partnerApp, "openid profile", ipAddress = "203.0.113.9")
        service.grant(tenantId, alice, partnerApp, "openid email", ipAddress = null)

        assertEquals(setOf("openid", "profile", "email"), consents.find(tenantId, alice, partnerApp.id)?.scopes)
        val first = audit.events.first()
        assertEquals(AuditEventType.CONSENT_GRANTED, first.eventType)
        assertEquals(partnerApp.id, first.clientId)
        assertEquals("openid profile", first.details["scopes"])
        assertEquals("203.0.113.9", first.ipAddress)
    }

    @Test
    fun `a denial is recorded and grants nothing`() {
        service.deny(tenantId, alice, partnerApp, "openid", ipAddress = null)

        assertTrue(audit.hasEvent(AuditEventType.CONSENT_DENIED))
        assertNull(consents.find(tenantId, alice, partnerApp.id))
    }

    @Test
    fun `the screen describes OpenID scopes, described API scopes, and bare ones`() {
        val api =
            apis.create(
                tenantId,
                "https://api.acme.test",
                "Orders API",
                null,
                scopes = listOf("orders:read", "orders:write"),
                scopeDescriptions = mapOf("orders:read" to "See your orders"),
            )
        apis.registerClient(partnerApp.id, tenantId)
        apis.setAuthorizedResources(partnerApp.id, listOf(api.id!!))

        val lines = service.describe(partnerApp, "openid orders:read orders:write offline_access")

        assertEquals(
            listOf(
                ConsentScope("openid"),
                ConsentScope("orders:read", "Orders API", "See your orders"),
                ConsentScope("orders:write", "Orders API", null),
                ConsentScope("offline_access"),
            ),
            lines,
        )
        assertTrue(lines.first().isIdentity)
    }

    @Test
    fun `revoking ends that application's sessions and no other's`() {
        service.grant(tenantId, alice, partnerApp, "openid", ipAddress = null)
        val partnerSession = sessions.save(session(partnerApp.id, "a"))
        val ownSession = sessions.save(session(ownApp.id, "b"))

        assertTrue(service.revokeOwn(tenantId, alice, partnerApp.id, ipAddress = null))

        assertNull(consents.find(tenantId, alice, partnerApp.id))
        assertEquals(ConsentService.REVOCATION_REASON, sessions.findById(partnerSession.id!!)?.revocationReason)
        assertNull(sessions.findById(ownSession.id!!)?.revokedAt)
        val event = audit.events.last()
        assertEquals(AuditEventType.CONSENT_REVOKED, event.eventType)
        assertEquals(alice, event.userId)
        assertFalse(service.revokeOwn(tenantId, alice, partnerApp.id, ipAddress = null), "nothing left to revoke")
    }

    @Test
    fun `an administrator revocation names the administrator and the user`() {
        service.grant(tenantId, alice, partnerApp, "openid", ipAddress = null)

        service.revokeByAdmin(tenantId, alice, partnerApp.id, actorId = UserId(1), ipAddress = null)

        val event = audit.events.last()
        assertEquals(AuditEventType.ADMIN_CONSENT_REVOKED, event.eventType)
        assertEquals(UserId(1), event.userId)
        assertEquals("10", event.details["targetUser"])
    }

    @Test
    fun `the user's list leaves out applications that no longer exist`() {
        service.grant(tenantId, alice, partnerApp, "openid", ipAddress = null)
        consents.save(tenantId, alice, ApplicationId(99), setOf("openid"), now)

        assertEquals(listOf("partner-app"), service.grantsFor(tenantId, alice).map { it.application.clientId })
    }

    @Test
    fun `a disabled application has no consent request to show`() {
        apps.add(partnerApp.copy(enabled = false))

        assertNull(service.request(tenantId, alice, "partner-app", "openid"))
    }

    private fun app(
        id: ApplicationId,
        clientId: String,
        consentRequired: Boolean,
    ) = Application(
        id = id,
        tenantId = tenantId,
        clientId = clientId,
        name = clientId,
        description = null,
        accessType = AccessType.PUBLIC,
        enabled = true,
        redirectUris = listOf("https://$clientId.test/callback"),
        grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
        consentRequired = consentRequired,
    )

    private fun session(
        appId: ApplicationId,
        hash: String,
    ) = Session(
        tenantId = tenantId,
        userId = alice,
        clientId = appId,
        accessTokenHash = hash,
        refreshTokenHash = "r$hash",
        expiresAt = Instant.now().plusSeconds(3600),
    )
}
