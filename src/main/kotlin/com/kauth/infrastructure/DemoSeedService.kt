package com.kauth.infrastructure

import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.ThemeRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.port.WebhookEndpointRepository
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.RoleGroupService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Seeds realistic demo data when KAUTH_DEMO_MODE=true.
 *
 * Designed for public showcase deployments (e.g. demo.kotauth.com) where
 * visitors should see a pre-populated instance rather than an empty shell.
 *
 * Idempotent: checks for existing "acme" tenant before seeding.
 * Uses repository ports directly (no HTTP layer) to keep it fast and reliable.
 */
class DemoSeedService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val applicationRepository: ApplicationRepository,
    private val passwordHasher: PasswordHasher,
    private val keyProvisioningService: KeyProvisioningService,
    private val portalClientProvisioning: PortalClientProvisioning,
    private val roleGroupService: RoleGroupService,
    private val roleRepository: RoleRepository,
    private val auditLog: AuditLogPort,
    private val webhookEndpointRepository: WebhookEndpointRepository,
    private val themeRepository: ThemeRepository,
    private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger("com.kauth.demo")

    companion object {
        const val DEMO_PASSWORD = "Demo1234!"
    }

    fun seedIfEmpty() {
        if (tenantRepository.existsBySlug("acme")) {
            log.info("Demo data already exists — skipping seed")
            return
        }

        log.info("Seeding demo data …")
        val start = System.currentTimeMillis()

        val passwordHash = passwordHasher.hash(DEMO_PASSWORD)

        val acme = seedAcmeWorkspace(passwordHash)
        val startupLabs = seedStartupLabsWorkspace(passwordHash)

        seedAuditEntries(acme.tenantId, acme.userIds)
        seedAuditEntries(startupLabs.tenantId, startupLabs.userIds)

        val elapsed = System.currentTimeMillis() - start
        log.info(
            "Demo seed complete in {}ms: 2 workspaces, {} users, {} apps, {} roles, {} groups",
            elapsed,
            acme.userIds.size + startupLabs.userIds.size,
            acme.appCount + startupLabs.appCount,
            acme.roleCount + startupLabs.roleCount,
            acme.groupCount + startupLabs.groupCount,
        )
    }

    // ── Acme Corp ────────────────────────────────────────────────────────

    private fun seedAcmeWorkspace(passwordHash: String): SeedResult {
        val tenant = tenantRepository.create("acme", "Acme Corp")
        themeRepository.upsert(tenant.id, TenantTheme.DEFAULT)
        val updated =
            tenantRepository.update(
                tenant.copy(
                    registrationEnabled = true,
                    emailVerificationRequired = false,
                    securityConfig =
                        tenant.securityConfig.copy(
                            passwordMinLength = 8,
                            passwordRequireUppercase = true,
                            passwordRequireNumber = true,
                            mfaPolicy = "optional",
                        ),
                    tokenExpirySeconds = 3600L,
                    refreshTokenExpirySeconds = 86400L,
                ),
            )
        keyProvisioningService.provisionForTenant(updated)
        portalClientProvisioning.provisionRedirectUris()

        // Baseline roles (also seeded by V16 migration, but created here
        // explicitly so DemoSeedService is self-contained and testable
        // without migrations. createRole returns Conflict if already present.)
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "admin",
            description = "Full administrative access",
            scope = RoleScope.TENANT,
            clientId = null,
        )
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "user",
            description = "Standard user access",
            scope = RoleScope.TENANT,
            clientId = null,
        )
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "billing-admin",
            description = "Manage billing and invoices",
            scope = RoleScope.TENANT,
            clientId = null,
        )
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "viewer",
            description = "Read-only access to resources",
            scope = RoleScope.TENANT,
            clientId = null,
        )

        // Applications
        val dashboard =
            applicationRepository.create(
                tenantId = updated.id,
                clientId = "acme-dashboard",
                name = "Acme Dashboard",
                description = "Internal admin dashboard",
                accessType = "confidential",
                redirectUris = listOf("$baseUrl/callback", "http://localhost:3000/callback"),
            )
        applicationRepository.create(
            tenantId = updated.id,
            clientId = "acme-mobile",
            name = "Acme Mobile App",
            description = "iOS and Android mobile application",
            accessType = "public",
            redirectUris = listOf("com.acme.mobile://callback"),
        )

        // Client-scoped role (requires app to exist first)
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "api-consumer",
            description = "Programmatic API access",
            scope = RoleScope.CLIENT,
            clientId = dashboard.id,
        )

        // Users
        val sarahId = saveUser(updated.id, "sarah.chen", "sarah@acme.example", "Sarah Chen", passwordHash).id!!
        val jamesId = saveUser(updated.id, "james.wilson", "james@acme.example", "James Wilson", passwordHash).id!!
        val mariaId = saveUser(updated.id, "maria.garcia", "maria@acme.example", "Maria Garcia", passwordHash).id!!
        val alexId = saveUser(updated.id, "alex.kumar", "alex@acme.example", "Alex Kumar", passwordHash).id!!
        val testUserId = saveUser(updated.id, "test.user", "test@acme.example", "Test User", passwordHash).id!!

        // Role assignments
        assignRole(updated.id, sarahId, "admin")
        assignRole(updated.id, jamesId, "user")
        assignRole(updated.id, mariaId, "billing-admin")
        assignRole(updated.id, alexId, "user")
        assignRole(updated.id, alexId, "viewer")
        assignRole(updated.id, testUserId, "user")

        // Groups
        val engineering =
            roleGroupService.createGroup(
                tenantId = updated.id,
                name = "Engineering",
                description = "Engineering department",
                parentGroupId = null,
            )
        val operations =
            roleGroupService.createGroup(
                tenantId = updated.id,
                name = "Operations",
                description = "Operations team",
                parentGroupId = null,
            )

        // Group memberships
        extractGroupId(engineering)?.let { gid ->
            roleGroupService.addUserToGroup(jamesId, gid, updated.id)
            roleGroupService.addUserToGroup(alexId, gid, updated.id)
            assignGroupRole(updated.id, gid, "user")
            assignGroupRole(updated.id, gid, "viewer")
        }
        extractGroupId(operations)?.let { gid ->
            roleGroupService.addUserToGroup(mariaId, gid, updated.id)
            assignGroupRole(updated.id, gid, "billing-admin")
        }

        // Webhook
        webhookEndpointRepository.save(
            WebhookEndpoint(
                tenantId = updated.id,
                url = "https://webhook.site/demo-acme",
                secret = "whsec_demo_acme_secret_key_123",
                events = setOf("user.created", "login.success"),
                description = "Acme webhook integration",
            ),
        )

        return SeedResult(
            tenantId = updated.id,
            userIds = listOf(sarahId, jamesId, mariaId, alexId, testUserId),
            appCount = 2,
            roleCount = 5,
            groupCount = 2,
        )
    }

    // ── Startup Labs ─────────────────────────────────────────────────────

    private fun seedStartupLabsWorkspace(passwordHash: String): SeedResult {
        val tenant = tenantRepository.create("startup-labs", "Startup Labs")
        themeRepository.upsert(tenant.id, TenantTheme.LIGHT)
        val updated =
            tenantRepository.update(
                tenant.copy(
                    registrationEnabled = true,
                    emailVerificationRequired = false,
                    securityConfig =
                        tenant.securityConfig.copy(
                            passwordMinLength = 6,
                            mfaPolicy = "optional",
                        ),
                    tokenExpirySeconds = 7200L,
                    refreshTokenExpirySeconds = 172800L,
                ),
            )
        keyProvisioningService.provisionForTenant(updated)
        portalClientProvisioning.provisionRedirectUris()

        // Baseline roles (same reasoning as Acme — self-contained seeding)
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "admin",
            description = "Full administrative access",
            scope = RoleScope.TENANT,
            clientId = null,
        )
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "user",
            description = "Standard user access",
            scope = RoleScope.TENANT,
            clientId = null,
        )
        roleGroupService.createRole(
            tenantId = updated.id,
            name = "developer",
            description = "Development team member",
            scope = RoleScope.TENANT,
            clientId = null,
        )

        // Application
        applicationRepository.create(
            tenantId = updated.id,
            clientId = "sl-webapp",
            name = "Startup Labs Web",
            description = "Main web application",
            accessType = "confidential",
            redirectUris = listOf("$baseUrl/callback", "http://localhost:5173/callback"),
        )

        // Users
        val jordanId = saveUser(updated.id, "jordan.lee", "jordan@startup.example", "Jordan Lee", passwordHash).id!!
        val caseyId = saveUser(updated.id, "casey.smith", "casey@startup.example", "Casey Smith", passwordHash).id!!
        val rileyId = saveUser(updated.id, "riley.jones", "riley@startup.example", "Riley Jones", passwordHash).id!!

        // Role assignments
        assignRole(updated.id, jordanId, "admin")
        assignRole(updated.id, caseyId, "user")
        assignRole(updated.id, caseyId, "developer")
        assignRole(updated.id, rileyId, "user")

        // Group
        val coreTeam =
            roleGroupService.createGroup(
                tenantId = updated.id,
                name = "Core Team",
                description = "Founding team",
                parentGroupId = null,
            )
        extractGroupId(coreTeam)?.let { gid ->
            roleGroupService.addUserToGroup(jordanId, gid, updated.id)
            roleGroupService.addUserToGroup(caseyId, gid, updated.id)
            assignGroupRole(updated.id, gid, "admin")
            assignGroupRole(updated.id, gid, "developer")
        }

        // Webhook
        webhookEndpointRepository.save(
            WebhookEndpoint(
                tenantId = updated.id,
                url = "https://webhook.site/demo-startup-labs",
                secret = "whsec_demo_sl_secret_key_456",
                events = setOf("user.created"),
                description = "Startup Labs notifications",
            ),
        )

        return SeedResult(
            tenantId = updated.id,
            userIds = listOf(jordanId, caseyId, rileyId),
            appCount = 1,
            roleCount = 3,
            groupCount = 1,
        )
    }

    // ── Audit log entries ────────────────────────────────────────────────

    private fun seedAuditEntries(
        tenantId: TenantId,
        userIds: List<UserId>,
    ) {
        val now = Instant.now()
        val events =
            listOf(
                AuditEventType.REGISTER_SUCCESS to 7,
                AuditEventType.LOGIN_SUCCESS to 6,
                AuditEventType.LOGIN_SUCCESS to 5,
                AuditEventType.TOKEN_ISSUED to 5,
                AuditEventType.ADMIN_USER_CREATED to 4,
                AuditEventType.LOGIN_SUCCESS to 3,
                AuditEventType.TOKEN_ISSUED to 3,
                AuditEventType.ADMIN_ROLE_ASSIGNED to 2,
                AuditEventType.LOGIN_SUCCESS to 1,
                AuditEventType.SESSION_CREATED to 1,
                AuditEventType.TOKEN_ISSUED to 0,
                AuditEventType.LOGIN_SUCCESS to 0,
            )

        events.forEachIndexed { idx, (eventType, daysAgo) ->
            val userId = userIds[idx % userIds.size]
            auditLog.record(
                AuditEvent(
                    tenantId = tenantId,
                    userId = userId,
                    clientId = null,
                    eventType = eventType,
                    ipAddress = "203.0.113.${10 + idx}",
                    userAgent = "Mozilla/5.0 (demo seed)",
                    details = mapOf("source" to "demo-seed"),
                    createdAt =
                        now
                            .minus(daysAgo.toLong(), ChronoUnit.DAYS)
                            .minus((idx * 37L) % 720, ChronoUnit.MINUTES),
                ),
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun saveUser(
        tenantId: TenantId,
        username: String,
        email: String,
        fullName: String,
        passwordHash: String,
    ): User =
        userRepository.save(
            User(
                tenantId = tenantId,
                username = username,
                email = email,
                fullName = fullName,
                passwordHash = passwordHash,
                emailVerified = true,
                enabled = true,
            ),
        )

    private fun assignRole(
        tenantId: TenantId,
        userId: UserId,
        roleName: String,
    ) {
        roleRepository.findByName(tenantId, roleName, RoleScope.TENANT)?.let { role ->
            roleGroupService.assignRoleToUser(userId, role.id!!, tenantId)
        }
    }

    private fun assignGroupRole(
        tenantId: TenantId,
        groupId: GroupId,
        roleName: String,
    ) {
        roleRepository.findByName(tenantId, roleName, RoleScope.TENANT)?.let { role ->
            roleGroupService.assignRoleToGroup(groupId, role.id!!, tenantId)
        }
    }

    private fun extractGroupId(result: AdminResult<Group>): GroupId? =
        when (result) {
            is AdminResult.Success -> result.value.id
            is AdminResult.Failure -> {
                log.warn("Failed to create group: {}", result.error.message)
                null
            }
        }

    private data class SeedResult(
        val tenantId: TenantId,
        val userIds: List<UserId>,
        val appCount: Int,
        val roleCount: Int,
        val groupCount: Int,
    )
}
