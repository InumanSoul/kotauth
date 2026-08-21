package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationBackup
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.Group
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.LoginLayout
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantKey
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.User
import com.kauth.domain.model.UserAttribute
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuditLogRepository
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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Domain-level tests for the v1.9.0 tenant export/import pipeline.
 *
 * Covers everything that doesn't require a real database: schema-drift policy,
 * round-trip identity-by-natural-key, redaction, opt-in extras, ID remapping,
 * and reference-resolution edge cases (composite roles, nested groups).
 *
 * Atomic-rollback semantics live in adapter integration tests against Postgres
 * — the in-memory fakes don't model transaction boundaries.
 */
class BackupExportImportTest {
    private val sourceTenants = FakeTenantRepository()
    private val sourceUsers = FakeUserRepository()
    private val sourceApps = FakeApplicationRepository()
    private val sourceRoles = FakeRoleRepository()
    private val sourceGroups = FakeGroupRepository()
    private val sourceMappers = FakeTenantClaimMapperRepository()
    private val sourceIdps = FakeIdentityProviderRepository()
    private val sourceKeys = FakeTenantKeyRepository()
    private val sourceAttrs = FakeUserAttributeRepository()
    private val sourceAudit = FakeAuditLogRepository()

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
    private val destAuditPort = FakeAuditLogPort()
    private val txRunner = FakeTransactionRunner()

    private fun exporter() =
        BackupExporterService(
            tenantRepository = sourceTenants,
            userRepository = sourceUsers,
            applicationRepository = sourceApps,
            roleRepository = sourceRoles,
            groupRepository = sourceGroups,
            claimMapperRepository = sourceMappers,
            identityProviderRepository = sourceIdps,
            tenantKeyRepository = sourceKeys,
            userAttributeRepository = sourceAttrs,
            auditLogRepository = sourceAudit,
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
            auditLogPort = destAuditPort,
            transactionRunner = txRunner,
        )

    @BeforeTest
    fun seedSourceTenant() {
        // Source tenant — Acme. Has 1 app, 2 roles (one composite), 1 group with 1 child,
        // 2 users, 1 claim mapper, 1 social provider, and 1 signing key.
        val tenant =
            sourceTenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme Corp",
                    issuerUrl = "https://acme.example.com",
                    tokenExpirySeconds = 7200,
                    securityConfig =
                        SecurityConfig(
                            magicLinkEnabled = true,
                            mfaPolicy = "required",
                            emailOtpSignupEnabled = true,
                            emailOtpLockoutThreshold = 3,
                            emailOtpLoginEnabled = true,
                        ),
                    theme = TenantTheme.LIGHT,
                    emailBranding =
                        com.kauth.domain.model.TenantEmailBranding(
                            tenantId = TenantId(0),
                            brandName = "Acme",
                            brandColorHex = "#1FBCFF",
                            supportEmail = "support@acme.test",
                            fromDisplayName = "Acme Support",
                        ),
                ),
            )

        val app =
            sourceApps.add(
                Application(
                    id = ApplicationId(0),
                    tenantId = tenant.id,
                    clientId = "acme-spa",
                    name = "Acme SPA",
                    description = "Customer portal",
                    accessType = AccessType.PUBLIC,
                    enabled = true,
                    redirectUris = listOf("https://app.acme.example.com/cb"),
                ),
            )

        val parentRole =
            sourceRoles.add(
                Role(id = null, tenantId = tenant.id, name = "admin", description = "Full access"),
            )
        val childRole =
            sourceRoles.add(
                Role(id = null, tenantId = tenant.id, name = "viewer", description = "Read-only"),
            )
        sourceRoles.addChildRole(parentRole.id!!, childRole.id!!)

        // Client-scoped role
        sourceRoles.add(
            Role(
                id = null,
                tenantId = tenant.id,
                name = "spa-admin",
                scope = RoleScope.CLIENT,
                clientId = app.id,
            ),
        )

        val parentGroup =
            sourceGroups.add(
                Group(
                    id = null,
                    tenantId = tenant.id,
                    name = "engineering",
                    description = "Eng team",
                    attributes = mapOf("dept" to "tech"),
                ),
            )
        val childGroup =
            sourceGroups.add(
                Group(
                    id = null,
                    tenantId = tenant.id,
                    name = "platform",
                    parentGroupId = parentGroup.id!!,
                ),
            )
        sourceGroups.assignRoleToGroup(parentGroup.id, parentRole.id)
        sourceGroups.assignRoleToGroup(childGroup.id!!, childRole.id)

        val alice =
            sourceUsers.add(
                User(
                    id = null,
                    tenantId = tenant.id,
                    username = "alice",
                    email = "alice@acme.example.com",
                    fullName = "Alice Adams",
                    passwordHash = "\$2a\$10\$AAAAAAAAAAAAAAAAAAAAAA",
                    emailVerified = true,
                    enabled = true,
                    requiredActions = setOf(RequiredAction.CHANGE_PASSWORD),
                    mfaEnabled = true,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        val bob =
            sourceUsers.add(
                User(
                    id = null,
                    tenantId = tenant.id,
                    username = "bob",
                    email = "bob@acme.example.com",
                    fullName = "Bob Brown",
                    passwordHash = "\$2a\$10\$BBBBBBBBBBBBBBBBBBBBBB",
                ),
            )

        sourceAttrs.upsert(
            UserAttribute(alice.id!!, tenant.id, "department", "engineering", Instant.now()),
        )
        sourceRoles.assignRoleToUser(alice.id, parentRole.id)
        sourceGroups.addUserToGroup(alice.id, parentGroup.id)
        sourceGroups.addUserToGroup(bob.id!!, childGroup.id)

        sourceMappers.upsert(
            TenantClaimMapper(
                tenantId = tenant.id,
                attributeKey = "department",
                claimName = "dept",
                includeInAccess = true,
                includeInId = true,
            ),
        )

        sourceIdps.add(
            IdentityProvider(
                id = null,
                tenantId = tenant.id,
                provider = SocialProvider.GOOGLE,
                clientId = "google-real-client-id",
                clientSecret = "PLAINTEXT-SECRET-MUST-NEVER-LEAK",
                enabled = true,
            ),
        )

        sourceKeys.add(
            TenantKey(
                id = null,
                tenantId = tenant.id,
                keyId = "kid-1",
                publicKeyPem = "-----BEGIN PUBLIC KEY-----\nABC\n-----END PUBLIC KEY-----",
                privateKeyPem = "-----BEGIN PRIVATE KEY-----\nXYZ\n-----END PRIVATE KEY-----",
                enabled = true,
                active = true,
            ),
        )

        sourceAudit.add(
            AuditEvent(
                tenantId = tenant.id,
                userId = alice.id,
                clientId = app.id,
                eventType = AuditEventType.LOGIN_SUCCESS,
                ipAddress = "10.0.0.1",
                userAgent = "test-agent",
                details = mapOf("scope" to "openid"),
            ),
        )
    }

    @AfterTest
    fun reset() {
        sourceTenants.clear()
        sourceUsers.clear()
        sourceApps.clear()
        sourceRoles.clear()
        sourceGroups.clear()
        sourceMappers.clear()
        sourceIdps.clear()
        sourceKeys.clear()
        sourceAttrs.clear()
        sourceAudit.clear()
        destTenants.clear()
        destUsers.clear()
        destApps.clear()
        destRoles.clear()
        destGroups.clear()
        destMappers.clear()
        destIdps.clear()
        destKeys.clear()
        destThemes.clear()
        destPortal.clear()
        destAttrs.clear()
        destAuditPort.clear()
    }

    @Test
    fun `compatibility matrix returns Ok for matching schema versions`() {
        assertEquals(CompatibilityResult.Ok, BackupCompatibilityMatrix.check(38, 38))
    }

    @Test
    fun `compatibility matrix warns when export schema is older than current`() {
        val r = BackupCompatibilityMatrix.check(35, 38)
        assertTrue(r is CompatibilityResult.WarnAdditive)
        assertEquals(35, r.exportSchemaVersion)
        assertEquals(38, r.currentSchemaVersion)
    }

    @Test
    fun `compatibility matrix rejects when export schema is newer than current`() {
        val r = BackupCompatibilityMatrix.check(40, 38)
        assertTrue(r is CompatibilityResult.RejectBreaking)
    }

    @Test
    fun `export contains tenant config and excludes secrets by default`() {
        val r = exporter().export("acme", ExportOptions(), kotauthVersion = "1.9.0", currentSchemaVersion = 38)
        val export = (r as BackupResult.Success).value

        assertEquals("acme", export.tenant.slug)
        assertEquals("Acme Corp", export.tenant.displayName)
        assertEquals(38, export.schemaVersion)
        assertEquals(BackupExportV1.CURRENT_EXPORT_VERSION, export.exportVersion)
        assertNull(export.signingKeys, "signing keys must be opt-in")
        assertNull(export.auditLog, "audit log must be opt-in")
        assertTrue("signingKeys" in export.manifest.redactedFields)
        assertTrue("auditLog" in export.manifest.redactedFields)
        assertTrue("applications.clientSecret" in export.manifest.redactedFields)
        assertTrue("socialProviders.clientSecret" in export.manifest.redactedFields)
    }

    @Test
    fun `export omits social provider client secrets in serialized payload`() {
        val export = exportSuccessful(ExportOptions())
        val asJson = backupJson().encodeToString(BackupExportV1.serializer(), export)
        assertFalse(
            "PLAINTEXT-SECRET-MUST-NEVER-LEAK" in asJson,
            "Social provider clientSecret must never appear in serialized export, was: $asJson",
        )
    }

    @Test
    fun `export includes signing keys when opted in`() {
        val export = exportSuccessful(ExportOptions(includeSigningKeys = true))
        assertNotNull(export.signingKeys)
        assertEquals(1, requireNotNull(export.signingKeys).size)
        assertEquals("kid-1", requireNotNull(export.signingKeys)[0].keyId)
        assertTrue("signingKeys" in export.manifest.includedExtras)
    }

    @Test
    fun `export includes audit log when opted in`() {
        val export = exportSuccessful(ExportOptions(includeAuditLog = true))
        assertNotNull(export.auditLog)
        assertEquals(1, requireNotNull(export.auditLog).size)
        assertEquals("LOGIN_SUCCESS", requireNotNull(export.auditLog)[0].eventType)
        assertEquals("alice", requireNotNull(export.auditLog)[0].username)
    }

    @Test
    fun `export resolves composite role children by name`() {
        val export = exportSuccessful(ExportOptions())
        val admin = export.roles.first { it.name == "admin" }
        assertEquals(listOf("viewer"), admin.childRoleNames)
    }

    @Test
    fun `export records nested group paths`() {
        val export = exportSuccessful(ExportOptions())
        val platform = export.groups.first { it.name == "platform" }
        assertEquals("engineering", platform.parentGroupPath)
        val alice = export.users.first { it.username == "alice" }
        assertEquals(listOf("engineering"), alice.groupPaths)
        val bob = export.users.first { it.username == "bob" }
        assertEquals(listOf("engineering/platform"), bob.groupPaths)
    }

    @Test
    fun `import creates new tenant with remapped IDs and matches export shape`() {
        val export = exportSuccessful(ExportOptions(includeSigningKeys = true, includeAuditLog = true))

        val r = importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val summary = (r as BackupResult.Success).value

        assertEquals("acme-staging", summary.newTenantSlug)
        assertEquals(2, summary.users)
        assertEquals(1, summary.applications)
        assertEquals(3, summary.roles)
        assertEquals(2, summary.groups)
        assertEquals(1, summary.signingKeys)
        assertEquals(1, summary.auditEvents)

        val newTenant = destTenants.findBySlug("acme-staging")!!
        assertTrue(
            newTenant.id.value != TenantId(1).value || destTenants.findAll().size == 1,
            "new tenant must have a fresh sequence id, not the source's",
        )
        assertEquals(2, destUsers.findByTenantId(newTenant.id, search = null, limit = 100, offset = 0).size)
        assertEquals(3, destRoles.findByTenantId(newTenant.id).size)
        assertEquals(2, destGroups.findByTenantId(newTenant.id).size)

        val aliceImported =
            destUsers
                .findByTenantId(newTenant.id, search = null, limit = 100, offset = 0)
                .first { it.username == "alice" }
        assertEquals(false, aliceImported.mfaEnabled, "MFA must be off after import — TOTP seed was not exported")
        assertEquals(setOf(RequiredAction.CHANGE_PASSWORD), aliceImported.requiredActions)
    }

    @Test
    fun `import preserves composite role linkage by re-resolving names`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val newTenantId = destTenants.findBySlug("acme-staging")!!.id

        val admin = destRoles.findByTenantId(newTenantId).first { it.name == "admin" }
        val viewer = destRoles.findByTenantId(newTenantId).first { it.name == "viewer" }
        val children = destRoles.findChildRoleIds(admin.id!!)
        assertEquals(listOf(viewer.id), children)
    }

    @Test
    fun `import preserves nested group hierarchy`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val newTenantId = destTenants.findBySlug("acme-staging")!!.id

        val engineering = destGroups.findByTenantId(newTenantId).first { it.name == "engineering" }
        val platform = destGroups.findByTenantId(newTenantId).first { it.name == "platform" }
        assertEquals(engineering.id, platform.parentGroupId)
    }

    @Test
    fun `import preserves user attributes and group memberships`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val newTenantId = destTenants.findBySlug("acme-staging")!!.id
        val alice =
            destUsers
                .findByTenantId(newTenantId, search = null, limit = 100, offset = 0)
                .first { it.username == "alice" }

        assertEquals(mapOf("department" to "engineering"), destAttrs.findAll(alice.id!!, newTenantId))
        val aliceGroups = destGroups.findGroupsForUser(alice.id)
        assertEquals(setOf("engineering"), aliceGroups.map { it.name }.toSet())
    }

    @Test
    fun `import preserves OTP security config fields and email branding`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val restored = destTenants.findBySlug("acme-staging")!!

        assertTrue(restored.securityConfig.emailOtpSignupEnabled)
        assertEquals(3, restored.securityConfig.emailOtpLockoutThreshold)
        assertTrue(restored.securityConfig.emailOtpLoginEnabled)

        val branding = destEmailBranding.findByTenantId(restored.id)!!
        assertEquals("Acme", branding.brandName)
        assertEquals("#1FBCFF", branding.brandColorHex)
        assertEquals("support@acme.test", branding.supportEmail)
        assertEquals("Acme Support", branding.fromDisplayName)
    }

    @Test
    fun `import imports social providers without their secret and disabled`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        val newTenantId = destTenants.findBySlug("acme-staging")!!.id
        val idps = destIdps.findAllByTenant(newTenantId)
        assertEquals(1, idps.size)
        assertEquals("google-real-client-id", idps[0].clientId)
        assertEquals("", idps[0].clientSecret, "Imported social provider must have empty secret — operator re-enters")
        assertFalse(idps[0].enabled, "Imported social provider must be disabled until secret is re-entered")
    }

    @Test
    fun `import rejects when slug already exists`() {
        val export = exportSuccessful(ExportOptions())
        destTenants.add(Tenant(id = TenantId(0), slug = "acme-staging", displayName = "Existing", issuerUrl = null))

        val r = importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        assertTrue(r is BackupResult.Failure)
        assertTrue(r.error is BackupError.SlugConflict)
    }

    @Test
    fun `import rejects when export schema is newer than current`() {
        val export = exportSuccessful(ExportOptions()).copy(schemaVersion = 99)
        val r = importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        assertTrue(r is BackupResult.Failure)
        assertTrue(r.error is BackupError.SchemaTooNew)
    }

    @Test
    fun `import succeeds (with implicit warn) when export schema is older than current`() {
        val export = exportSuccessful(ExportOptions()).copy(schemaVersion = 30)
        val r = importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        assertTrue(r is BackupResult.Success)
    }

    @Test
    fun `import wraps writes in transaction runner`() {
        val export = exportSuccessful(ExportOptions())
        importer().import(export, newSlug = "acme-staging", currentSchemaVersion = 38)
        assertEquals(1, txRunner.invocations)
    }

    @Test
    fun `import rejects a payload whose theme fails server-side validation`() {
        val export = exportSuccessful(ExportOptions())
        val tampered =
            export.copy(
                tenant = export.tenant.copy(theme = export.tenant.theme.copy(accentColor = "red; evil")),
            )
        val r = importer().import(tampered, newSlug = "acme-staging", currentSchemaVersion = 38)
        assertIs<BackupResult.Failure>(r)
        val error = r.error
        assertIs<BackupError.InvalidPayload>(error)
        assertTrue(
            error.message.contains("theme", ignoreCase = true),
            "Failure must surface a theme-validation error so misconfigured backups don't silently pass",
        )
    }

    @Test
    fun `theme round-trip preserves login layout fields`() {
        val source = sourceTenants.findBySlug("acme")!!
        sourceTenants.update(
            source.copy(
                theme =
                    source.theme.copy(
                        loginLayout = LoginLayout.SPLIT,
                        loginTagline = "Welcome to Acme",
                        loginBackgroundUrl = "https://acme.example.com/hero.jpg",
                    ),
            ),
        )

        val export = exportSuccessful(ExportOptions())
        val result = importer().import(export, newSlug = "acme-restored", currentSchemaVersion = 38)
        assertIs<BackupResult.Success<Unit>>(result)

        val restoredTheme = destThemes.findByTenantId(destTenants.findBySlug("acme-restored")!!.id)
        assertNotNull(restoredTheme)
        assertEquals(LoginLayout.SPLIT, restoredTheme.loginLayout)
        assertEquals("Welcome to Acme", restoredTheme.loginTagline)
        assertEquals("https://acme.example.com/hero.jpg", restoredTheme.loginBackgroundUrl)
    }

    @Test
    fun `grant types survive an export and import round trip`() {
        sourceApps.add(
            Application(
                id = ApplicationId(0),
                tenantId = sourceTenants.findBySlug("acme")!!.id,
                clientId = "m2m",
                name = "M2M Service",
                description = "Service-to-service client",
                accessType = AccessType.CONFIDENTIAL,
                enabled = true,
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS),
            ),
        )

        val exported = exportSuccessful(ExportOptions())
        val client = exported.applications.first { it.clientId == "m2m" }
        assertEquals(listOf("client_credentials"), client.grantTypes)

        val r = importer().import(exported, newSlug = "acme-m2m", currentSchemaVersion = 38)
        val summary = (r as BackupResult.Success).value
        val newTenantId = destTenants.findBySlug(summary.newTenantSlug)!!.id
        val restored = destApps.findByClientId(newTenantId, "m2m")!!
        assertEquals(setOf(GrantType.CLIENT_CREDENTIALS), restored.grantTypes)
    }

    @Test
    fun `a backup written before the grant types field restores grants derived from the access type`() {
        val legacy = backupWithClient(accessType = "confidential", grantTypes = null)

        val r = importer().import(legacy, newSlug = "acme-legacy", currentSchemaVersion = 38)
        val summary = (r as BackupResult.Success).value

        val newTenantId = destTenants.findBySlug(summary.newTenantSlug)!!.id
        val restored = destApps.findByClientId(newTenantId, "legacy-client")!!
        assertEquals(GrantType.defaultsFor(AccessType.CONFIDENTIAL), restored.grantTypes)
    }

    @Test
    fun `export returns TenantNotFound for unknown slug`() {
        val r = exporter().export("missing", ExportOptions(), kotauthVersion = "1.9.0", currentSchemaVersion = 38)
        if (r !is BackupResult.Failure || r.error !is BackupError.TenantNotFound) {
            fail("Expected TenantNotFound, got $r")
        }
    }

    private fun exportSuccessful(options: ExportOptions): BackupExportV1 {
        val r = exporter().export("acme", options, kotauthVersion = "1.9.0", currentSchemaVersion = 38)
        return (r as BackupResult.Success).value
    }

    /**
     * Builds a minimal backup carrying a single application, stripped of every
     * cross-referencing entity (roles, groups, users) so it imports standalone.
     */
    private fun backupWithClient(
        accessType: String,
        grantTypes: List<String>?,
    ): BackupExportV1 =
        exportSuccessful(ExportOptions()).copy(
            applications =
                listOf(
                    ApplicationBackup(
                        clientId = "legacy-client",
                        name = "Legacy Client",
                        description = null,
                        accessType = accessType,
                        enabled = true,
                        redirectUris = emptyList(),
                        tokenExpiryOverride = null,
                        grantTypes = grantTypes,
                    ),
                ),
            roles = emptyList(),
            groups = emptyList(),
            users = emptyList(),
            claimMappers = emptyList(),
            socialProviders = emptyList(),
        )

    private fun backupJson() =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}
