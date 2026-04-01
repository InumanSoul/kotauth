package com.kauth.infrastructure

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.WebhookEventType
import com.kauth.domain.service.RoleGroupService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebhookEndpointRepository
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DemoSeedService].
 *
 * All infrastructure deps (KeyProvisioningService, PortalClientProvisioning)
 * are relaxed mocks — we only care that seed logic populates the repositories.
 */
class DemoSeedServiceTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val hasher = FakePasswordHasher()
    private val roles = FakeRoleRepository()
    private val groups = FakeGroupRepository()
    private val auditLog = FakeAuditLogPort()
    private val webhooks = FakeWebhookEndpointRepository()
    private val keyProvisioning = mockk<KeyProvisioningService>(relaxed = true)
    private val portalProvisioning = mockk<PortalClientProvisioning>(relaxed = true)

    private val roleGroupService =
        RoleGroupService(
            roleRepository = roles,
            groupRepository = groups,
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            auditLog = auditLog,
        )

    private val themes = com.kauth.fakes.FakeThemeRepository()

    private val svc =
        DemoSeedService(
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            passwordHasher = hasher,
            keyProvisioningService = keyProvisioning,
            portalClientProvisioning = portalProvisioning,
            roleGroupService = roleGroupService,
            roleRepository = roles,
            auditLog = auditLog,
            webhookEndpointRepository = webhooks,
            themeRepository = themes,
            baseUrl = "http://localhost:8080",
        )

    @BeforeTest
    fun reset() {
        tenants.clear()
        users.clear()
        apps.clear()
        roles.clear()
        groups.clear()
        auditLog.clear()
        webhooks.clear()
    }

    // ── Idempotency ─────────────────────────────────────────────────────

    @Test
    fun `seedIfEmpty does nothing when acme tenant already exists`() {
        tenants.add(
            com.kauth.domain.model.Tenant(
                id = TenantId(99),
                slug = "acme",
                displayName = "Existing Acme",
                issuerUrl = null,
            ),
        )

        svc.seedIfEmpty()

        assertEquals(1, tenants.findAll().size, "No new tenants should be created")
        assertEquals(0, users.findByTenantId(TenantId(99)).size, "No users should be seeded")
    }

    @Test
    fun `seedIfEmpty can be called twice without duplicating data`() {
        svc.seedIfEmpty()
        val firstTenantCount = tenants.findAll().size
        val firstUserCount = users.findByTenantId(tenants.findBySlug("acme")!!.id).size

        svc.seedIfEmpty()

        assertEquals(firstTenantCount, tenants.findAll().size)
        assertEquals(firstUserCount, users.findByTenantId(tenants.findBySlug("acme")!!.id).size)
    }

    // ── Tenant creation ─────────────────────────────────────────────────

    @Test
    fun `seedIfEmpty creates acme and startup-labs tenants`() {
        svc.seedIfEmpty()

        val acme = tenants.findBySlug("acme")
        val sl = tenants.findBySlug("startup-labs")

        assertTrue(acme != null, "Acme tenant should exist")
        assertTrue(sl != null, "Startup Labs tenant should exist")
        assertEquals("Acme Corp", acme.displayName)
        assertEquals("Startup Labs", sl.displayName)
    }

    @Test
    fun `acme tenant has correct settings`() {
        svc.seedIfEmpty()

        val acme = tenants.findBySlug("acme")!!
        assertTrue(acme.registrationEnabled)
        assertEquals(8, acme.passwordPolicyMinLength)
        assertTrue(acme.passwordPolicyRequireUppercase)
        assertTrue(acme.passwordPolicyRequireNumber)
        assertEquals("optional", acme.mfaPolicy)
        assertEquals(3600L, acme.tokenExpirySeconds)
    }

    // ── Users ────────────────────────────────────────────────────────────

    @Test
    fun `acme workspace has 5 users with correct attributes`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val acmeUsers = users.findByTenantId(acmeId)

        assertEquals(5, acmeUsers.size)

        val sarah = users.findByUsername(acmeId, "sarah.chen")
        assertTrue(sarah != null)
        assertEquals("sarah@acme.example", sarah.email)
        assertEquals("Sarah Chen", sarah.fullName)
        assertTrue(sarah.emailVerified)
        assertTrue(sarah.enabled)
        assertEquals("hashed:${DemoSeedService.DEMO_PASSWORD}", sarah.passwordHash)
    }

    @Test
    fun `startup-labs workspace has 3 users`() {
        svc.seedIfEmpty()

        val slId = tenants.findBySlug("startup-labs")!!.id
        val slUsers = users.findByTenantId(slId)

        assertEquals(3, slUsers.size)

        val usernames = slUsers.map { it.username }.toSet()
        assertEquals(setOf("jordan.lee", "casey.smith", "riley.jones"), usernames)
    }

    // ── Applications ────────────────────────────────────────────────────

    @Test
    fun `acme workspace has 2 applications`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val acmeApps = apps.findByTenantId(acmeId)

        assertEquals(2, acmeApps.size)

        val dashboard = apps.findByClientId(acmeId, "acme-dashboard")
        assertTrue(dashboard != null)
        assertEquals("Acme Dashboard", dashboard.name)

        val mobile = apps.findByClientId(acmeId, "acme-mobile")
        assertTrue(mobile != null)
        assertEquals("Acme Mobile App", mobile.name)
    }

    @Test
    fun `startup-labs workspace has 1 application`() {
        svc.seedIfEmpty()

        val slId = tenants.findBySlug("startup-labs")!!.id
        val slApps = apps.findByTenantId(slId)

        assertEquals(1, slApps.size)
        assertEquals("sl-webapp", slApps.first().clientId)
    }

    // ── Roles ────────────────────────────────────────────────────────────

    @Test
    fun `acme workspace has baseline and custom roles`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val acmeRoles = roles.findByTenantId(acmeId)
        val roleNames = acmeRoles.map { it.name }.toSet()

        assertTrue("admin" in roleNames)
        assertTrue("user" in roleNames)
        assertTrue("billing-admin" in roleNames)
        assertTrue("viewer" in roleNames)
        assertTrue("api-consumer" in roleNames)
    }

    @Test
    fun `startup-labs workspace has developer role`() {
        svc.seedIfEmpty()

        val slId = tenants.findBySlug("startup-labs")!!.id
        val devRole = roles.findByName(slId, "developer", RoleScope.TENANT)
        assertTrue(devRole != null)
    }

    // ── Role assignments ────────────────────────────────────────────────

    @Test
    fun `sarah chen has admin role in acme`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val sarah = users.findByUsername(acmeId, "sarah.chen")!!
        val sarahRoles = roles.findRolesForUser(sarah.id!!)

        assertTrue(sarahRoles.any { it.name == "admin" })
    }

    @Test
    fun `alex kumar has both user and viewer roles`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val alex = users.findByUsername(acmeId, "alex.kumar")!!
        val alexRoles = roles.findRolesForUser(alex.id!!).map { it.name }.toSet()

        assertTrue("user" in alexRoles)
        assertTrue("viewer" in alexRoles)
    }

    // ── Groups ───────────────────────────────────────────────────────────

    @Test
    fun `acme workspace has engineering and operations groups`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val acmeGroups = groups.findByTenantId(acmeId)
        val groupNames = acmeGroups.map { it.name }.toSet()

        assertEquals(setOf("Engineering", "Operations"), groupNames)
    }

    @Test
    fun `engineering group contains james and alex`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val eng = groups.findByName(acmeId, "Engineering", null)!!
        val memberIds = groups.findUserIdsInGroup(eng.id!!)
        val james = users.findByUsername(acmeId, "james.wilson")!!
        val alex = users.findByUsername(acmeId, "alex.kumar")!!

        assertTrue(james.id!! in memberIds)
        assertTrue(alex.id!! in memberIds)
        assertEquals(2, memberIds.size)
    }

    // ── Webhooks ────────────────────────────────────────────────────────

    @Test
    fun `each workspace has one webhook endpoint`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val slId = tenants.findBySlug("startup-labs")!!.id

        assertEquals(1, webhooks.findByTenantId(acmeId).size)
        assertEquals(1, webhooks.findByTenantId(slId).size)
    }

    @Test
    fun `acme webhook has correct events`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val wh = webhooks.findByTenantId(acmeId).first()

        assertEquals(setOf(WebhookEventType.USER_CREATED, WebhookEventType.LOGIN_SUCCESS), wh.events)
        assertEquals("https://webhook.site/demo-acme", wh.url)
    }

    // ── Audit log ───────────────────────────────────────────────────────

    @Test
    fun `seedIfEmpty creates audit log entries for both workspaces`() {
        svc.seedIfEmpty()

        assertTrue(auditLog.events.isNotEmpty())
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_SUCCESS))
        assertTrue(auditLog.hasEvent(AuditEventType.REGISTER_SUCCESS))
        assertTrue(auditLog.hasEvent(AuditEventType.TOKEN_ISSUED))
    }

    @Test
    fun `audit entries are created for both tenants`() {
        svc.seedIfEmpty()

        val acmeId = tenants.findBySlug("acme")!!.id
        val slId = tenants.findBySlug("startup-labs")!!.id

        // Filter to only the backdated seed entries (RoleGroupService also
        // fires audit events for role/group CRUD — those are valid but not
        // what this test targets).
        val acmeSeedEvents =
            auditLog.events.filter {
                it.tenantId == acmeId && it.details["source"] == "demo-seed"
            }
        val slSeedEvents =
            auditLog.events.filter {
                it.tenantId == slId && it.details["source"] == "demo-seed"
            }

        assertEquals(12, acmeSeedEvents.size, "12 backdated audit entries for Acme")
        assertEquals(12, slSeedEvents.size, "12 backdated audit entries for Startup Labs")

        // Both tenants should also have operational audit events from RoleGroupService
        val acmeTotal = auditLog.events.count { it.tenantId == acmeId }
        val slTotal = auditLog.events.count { it.tenantId == slId }
        assertTrue(acmeTotal > 12, "Acme should have seed + operational audit events")
        assertTrue(slTotal > 12, "Startup Labs should have seed + operational audit events")
    }

    // ── Cross-workspace isolation ───────────────────────────────────────

    @Test
    fun `acme users are not visible in startup-labs`() {
        svc.seedIfEmpty()

        val slId = tenants.findBySlug("startup-labs")!!.id

        val sarah = users.findByUsername(slId, "sarah.chen")
        assertTrue(sarah == null, "Acme user should not appear in Startup Labs")
    }
}
