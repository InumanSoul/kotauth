package com.kauth.domain.service

import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.fakes.FakeApplicationRepository
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a restore does with a username the format rule now forbids.
 *
 * Originally this rejected every such record. That made a path the compatibility matrix declares
 * supported into a dead end: migration V66 rewrites exactly these values in place on the upgrade
 * path, but exports are passphrase-encrypted with no decrypt-and-edit route, so an operator
 * holding a pre-1.24 backup had nothing to fix and no way to fix it (#141).
 *
 * The rule now is: rewrite what V66 would rewrite, report every change in the summary, and keep
 * rejecting only the two cases V66 itself aborts on — a username that collapses to nothing, and
 * two that collapse together. Those destroy identity rather than reshape an identifier.
 *
 * Separate from [BackupExportImportTest] since it exercises paths that test's fixtures don't cover.
 */
class BackupImporterUsernameValidationTest {
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
            auditLogPort = null,
            // Rolls back the entities the fakes can snapshot on a thrown exception. Full
            // cross-entity atomicity (including tenant creation) is only modeled against a real
            // Postgres transaction — see BackupExportImportTest's class doc for the same caveat.
            transactionRunner = FakeTransactionRunner(destUsers, destGroups),
        )

    private fun seedSourceTenantWithOneUser(): TenantId {
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
        return tenant.id
    }

    private fun exportAcme(): BackupExportV1 {
        seedSourceTenantWithOneUser()
        val result = exporter().export("acme", ExportOptions(), kotauthVersion = "test", currentSchemaVersion = 1)
        return (result as BackupResult.Success).value
    }

    private fun seedSourceTenantWithTwoUsers(): TenantId {
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
        sourceUsers.add(
            User(
                id = null,
                tenantId = tenant.id,
                username = "bob",
                email = "bob@acme.example.com",
                fullName = "Bob Baker",
                passwordHash = "\$2a\$10\$AAAAAAAAAAAAAAAAAAAAAA",
            ),
        )
        return tenant.id
    }

    private fun exportAcmeWithTwoUsers(): BackupExportV1 {
        seedSourceTenantWithTwoUsers()
        val result = exporter().export("acme", ExportOptions(), kotauthVersion = "test", currentSchemaVersion = 1)
        return (result as BackupResult.Success).value
    }

    @Test
    fun `import rewrites a legacy username the way V66 would, and says so`() {
        val export = exportAcme()
        val legacy = export.copy(users = listOf(export.users.single().copy(username = "John Doe")))

        val result = importer().import(legacy, newSlug = "acme-restored", currentSchemaVersion = 1)

        val summary = assertIs<BackupResult.Success<ImportSummary>>(result).value
        val restoredTenant = destTenants.findBySlug("acme-restored")!!
        val restoredUser = destUsers.findByTenantId(restoredTenant.id, null, 100, 0).single()
        assertEquals("john.doe", restoredUser.username, "must match V66's rewrite of the same value")

        val rewrite = summary.usernameRewrites.single()
        assertEquals("John Doe", rewrite.from)
        assertEquals("john.doe", rewrite.to)
        assertEquals("alice@acme.example.com", rewrite.email)
    }

    @Test
    fun `a username that rewrites cleanly is not reported as a rewrite`() {
        val export = exportAcme()

        val result = importer().import(export, newSlug = "acme-restored", currentSchemaVersion = 1)

        val summary = assertIs<BackupResult.Success<ImportSummary>>(result).value
        assertTrue(
            summary.usernameRewrites.isEmpty(),
            "an already-valid username is unchanged, so nothing should be reported: " +
                "${summary.usernameRewrites}",
        )
    }

    @Test
    fun `import rejects a username that rewrites to nothing`() {
        val export = exportAcme()
        // Entirely outside [a-z0-9._@+-] — collapses to "", exactly the case V66 aborts on.
        val badExport = export.copy(users = listOf(export.users.single().copy(username = "用户")))

        val result = importer().import(badExport, newSlug = "acme-restored", currentSchemaVersion = 1)

        assertIs<BackupResult.Failure>(result)
        val error = result.error
        assertIs<BackupError.InvalidPayload>(error)
        assertContains(error.message, "alice@acme.example.com")
        val createdTenant = destTenants.findBySlug("acme-restored")
        if (createdTenant != null) {
            assertTrue(destUsers.findByTenantId(createdTenant.id, null, 100, 0).isEmpty())
        }
    }

    @Test
    fun `import rejects two usernames that would collide after rewriting`() {
        val export = exportAcmeWithTwoUsers()
        val badExport =
            export.copy(
                users =
                    export.users.map {
                        when (it.username) {
                            "alice" -> it.copy(username = "John Doe")
                            "bob" -> it.copy(username = "john/doe")
                            else -> it
                        }
                    },
            )

        val result = importer().import(badExport, newSlug = "acme-restored", currentSchemaVersion = 1)

        assertIs<BackupResult.Failure>(result)
        val error = result.error
        assertIs<BackupError.InvalidPayload>(error)
        // Merging or dropping an identity row is never an acceptable restore outcome, so this
        // stays a refusal even though both values rewrite to something valid on their own.
        assertContains(error.message, "john.doe")
        assertContains(error.message, "alice@acme.example.com")
        assertContains(error.message, "bob@acme.example.com")
        val createdTenant = destTenants.findBySlug("acme-restored")
        if (createdTenant != null) {
            assertTrue(destUsers.findByTenantId(createdTenant.id, null, 100, 0).isEmpty())
        }
    }

    @Test
    fun `import reports every unrewritable record in one failure, not just the first`() {
        val export = exportAcmeWithTwoUsers()
        val badExport =
            export.copy(
                users =
                    export.users.map {
                        when (it.username) {
                            "alice" -> it.copy(username = "用户")
                            "bob" -> it.copy(username = "a".repeat(UsernamePolicy.MAX_LENGTH + 1))
                            else -> it
                        }
                    },
            )

        val result = importer().import(badExport, newSlug = "acme-restored", currentSchemaVersion = 1)

        assertIs<BackupResult.Failure>(result)
        val error = result.error
        assertIs<BackupError.InvalidPayload>(error)
        // Both offenders in the SAME failure — an operator restoring many users must see the
        // full list at once, not discover them one aborted retry at a time.
        assertContains(error.message, "alice@acme.example.com")
        assertContains(error.message, "bob@acme.example.com")
        val createdTenant = destTenants.findBySlug("acme-restored")
        if (createdTenant != null) {
            assertTrue(destUsers.findByTenantId(createdTenant.id, null, 100, 0).isEmpty())
        }
    }

    @Test
    fun `import accepts a username that normalizes cleanly`() {
        val export = exportAcme()
        val messyExport = export.copy(users = listOf(export.users.single().copy(username = "  Alice ")))

        val result = importer().import(messyExport, newSlug = "acme-restored", currentSchemaVersion = 1)

        assertIs<BackupResult.Success<ImportSummary>>(result)
        val restoredTenant = destTenants.findBySlug("acme-restored")!!
        val restoredUser = destUsers.findByTenantId(restoredTenant.id, null, 100, 0).single()
        assertEquals("alice", restoredUser.username)
    }
}
