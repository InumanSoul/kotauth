package com.kauth.domain.service

import com.kauth.domain.model.AuditEventBackup
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePortalConfigRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Restoring a legacy (pre-1.24) backup must not silently drop the user link on audit events whose
 * `username` was captured before usernames were normalized to trimmed-lowercase form. See FIX 2 of
 * the login-identifier code review: `userPkByUsername` was keyed only by the normalized username,
 * while audit events were resolved against the original exported (possibly mixed-case) value.
 */
class BackupImporterAuditLinkTest {
    private val sourceTenants = FakeTenantRepository()
    private val sourceUsers = FakeUserRepository()

    private val destTenants = FakeTenantRepository()
    private val destUsers = FakeUserRepository()
    private val destApps = FakeApplicationRepository()
    private val destRoles = FakeRoleRepository()
    private val destGroups = FakeGroupRepository()
    private val destMappers = FakeTenantClaimMapperRepository()
    private val destIdps = FakeIdentityProviderRepository()
    private val destKeys = FakeTenantKeyRepository()
    private val destThemes = FakeThemeRepository()
    private val destPortal = FakePortalConfigRepository()
    private val destAttrs = FakeUserAttributeRepository()
    private val destEmailBranding = FakeTenantEmailBrandingRepository()
    private val destAuditLog = FakeAuditLogPort()

    private fun exporter() =
        BackupExporterService(
            tenantRepository = sourceTenants,
            userRepository = sourceUsers,
            applicationRepository = FakeApplicationRepository(),
            roleRepository = FakeRoleRepository(),
            groupRepository = FakeGroupRepository(),
            claimMapperRepository = FakeTenantClaimMapperRepository(),
            identityProviderRepository = FakeIdentityProviderRepository(),
            tenantKeyRepository = FakeTenantKeyRepository(),
            userAttributeRepository = FakeUserAttributeRepository(),
            auditLogRepository = null,
        )

    private fun importer() =
        BackupImporterService(
            tenantRepository = destTenants,
            userRepository = destUsers,
            applicationRepository = destApps,
            roleRepository = destRoles,
            groupRepository = destGroups,
            claimMapperRepository = destMappers,
            identityProviderRepository = destIdps,
            tenantKeyRepository = destKeys,
            themeRepository = destThemes,
            portalConfigRepository = destPortal,
            userAttributeRepository = destAttrs,
            emailBrandingRepository = destEmailBranding,
            auditLogPort = destAuditLog,
            transactionRunner = FakeTransactionRunner(destUsers, destGroups),
        )

    private fun exportAcme(): BackupExportV1 {
        val tenant =
            sourceTenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme Corp",
                    issuerUrl = "https://acme.example.com",
                ),
            )
        sourceUsers.add(
            User(
                id = null,
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.example.com",
                fullName = "Alice Adams",
                passwordHash = "\$2a\$10\$AAAAAAAAAAAAAAAAAAAAAA",
            ),
        )
        val result = exporter().export("acme", ExportOptions(), kotauthVersion = "test", currentSchemaVersion = 1)
        return (result as BackupResult.Success).value
    }

    @Test
    fun `import links an audit event that references the original, non-normalized username`() {
        val export = exportAcme()
        // Simulate a pre-1.24 backup: the stored username was normalized to "alice" on restore,
        // but the audit event was captured back when the exported record still carried "Dave"-style
        // mixed case — here "Alice" is the original, unnormalized casing.
        val legacyExport =
            export.copy(
                users = listOf(export.users.single().copy(username = "Alice")),
                auditLog =
                    listOf(
                        AuditEventBackup(
                            createdAt = 1_700_000_000L,
                            username = "Alice",
                            clientId = null,
                            eventType = AuditEventType.LOGIN_SUCCESS.name,
                            ipAddress = null,
                            userAgent = null,
                            details = emptyMap(),
                        ),
                    ),
            )

        val result = importer().import(legacyExport, newSlug = "acme-restored", currentSchemaVersion = 1)

        assertIs<BackupResult.Success<ImportSummary>>(result)
        val restoredTenant = destTenants.findBySlug("acme-restored")!!
        val restoredUser = destUsers.findByTenantId(restoredTenant.id, null, 100, 0).single()
        assertEquals("alice", restoredUser.username)

        val restoredEvent = destAuditLog.events.single()
        assertNotNull(restoredEvent.userId, "Audit event must still be linked to the restored user")
        assertEquals(restoredUser.id, restoredEvent.userId)
    }
}
