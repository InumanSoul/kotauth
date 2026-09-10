package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePortalConfigRepository
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a backup carries, and what survives a restore.
 *
 * Every field asserted here used to be dropped. An identity provider came back as `oauth2` with
 * no issuer and just-in-time provisioning silently off (#130); an application lost its audience
 * and its whole launcher configuration; and the resource servers a workspace's machine-to-machine
 * clients were built around were not exported at all, so a restored workspace looked configured
 * and behaved differently with nothing telling the operator what was missing.
 *
 * These are round-trip assertions rather than shape assertions on purpose: a field added to the
 * serialized model but never read back by the importer is exactly the failure this is guarding,
 * and only the round trip catches it.
 */
class BackupFidelityTest {
    private val sourceTenants = FakeTenantRepository()
    private val sourceUsers = FakeUserRepository()
    private val sourceApps = FakeApplicationRepository()
    private val sourceIdps = FakeIdentityProviderRepository()
    private val sourceResourceServers = FakeResourceServerRepository(sourceApps)

    private val destTenants = FakeTenantRepository()
    private val destUsers = FakeUserRepository()
    private val destApps = FakeApplicationRepository()
    private val destIdps = FakeIdentityProviderRepository()
    private val destResourceServers = FakeResourceServerRepository(destApps)

    private fun exporter() =
        BackupExporterService(
            tenantRepository = sourceTenants,
            userRepository = sourceUsers,
            applicationRepository = sourceApps,
            roleRepository = FakeRoleRepository(),
            groupRepository = FakeGroupRepository(),
            claimMapperRepository = FakeTenantClaimMapperRepository(),
            identityProviderRepository = sourceIdps,
            tenantKeyRepository = FakeTenantKeyRepository(),
            userAttributeRepository = FakeUserAttributeRepository(),
            auditLogRepository = null,
            resourceServerRepository = sourceResourceServers,
        )

    private fun importer() =
        BackupImporterService(
            tenantRepository = destTenants,
            userRepository = destUsers,
            applicationRepository = destApps,
            roleRepository = FakeRoleRepository(),
            groupRepository = FakeGroupRepository(),
            claimMapperRepository = FakeTenantClaimMapperRepository(),
            identityProviderRepository = destIdps,
            tenantKeyRepository = FakeTenantKeyRepository(),
            themeRepository = FakeThemeRepository(),
            portalConfigRepository = FakePortalConfigRepository(),
            userAttributeRepository = FakeUserAttributeRepository(),
            emailBrandingRepository = FakeTenantEmailBrandingRepository(),
            auditLogPort = null,
            transactionRunner = FakeTransactionRunner(),
            resourceServerRepository = destResourceServers,
        )

    @BeforeTest
    fun setup() {
        val tenant =
            sourceTenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme Corp",
                    issuerUrl = "https://acme.example.com",
                ),
            )
        val app =
            sourceApps.add(
                Application(
                    id = ApplicationId(0),
                    tenantId = tenant.id,
                    clientId = "acme-m2m",
                    name = "Acme Ingest Worker",
                    description = "Machine-to-machine ingest",
                    accessType = AccessType.CONFIDENTIAL,
                    enabled = true,
                    redirectUris = emptyList(),
                    audience = "https://api.acme.example.com",
                    launcherUrl = "https://ingest.acme.example.com",
                    iconUrl = "https://cdn.acme.example.com/ingest.png",
                    launcherVisible = false,
                    launcherDisplayOrder = 7,
                ),
            )

        // Declares three scopes; the client is granted exactly one of them. That gap is the whole
        // point of ADR-23, and it is the thing a restore must not quietly close.
        val api =
            sourceResourceServers.seed(
                ResourceServer(
                    id = null,
                    tenantId = tenant.id,
                    identifier = "https://api.acme.example.com",
                    name = "Acme API",
                    description = "Public data API",
                    enabled = true,
                    scopes = listOf("assistant:ingest", "assistant:keys", "assistant:handoff"),
                ),
            )
        sourceResourceServers.setAuthorizedResources(app.id, listOf(api.id!!))
        sourceResourceServers.setAllowedScopes(app.id, api.id!!, setOf("assistant:ingest"))

        sourceIdps.add(
            IdentityProvider(
                id = null,
                tenantId = tenant.id,
                provider = ProviderKey.GOOGLE,
                clientId = "google-real-client-id",
                clientSecret = "PLAINTEXT-SECRET-MUST-NEVER-LEAK",
                enabled = true,
                kind = ProviderKind.OIDC,
                displayName = "Acme Google",
                issuer = "https://accounts.google.com",
                authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenEndpoint = "https://oauth2.googleapis.com/token",
                jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
                scopes = "openid email profile groups",
                jitEnabled = true,
                jitAllowedDomains = listOf("acme.example.com", "acme.co"),
                trustEmailClaim = true,
            ),
        )
    }

    private fun exportAcme(): BackupExportV1 {
        val result = exporter().export("acme", ExportOptions(), kotauthVersion = "test", currentSchemaVersion = 1)
        return assertIs<BackupResult.Success<BackupExportV1>>(result).value
    }

    private fun restore(export: BackupExportV1): ImportSummary {
        val result = importer().import(export, newSlug = "acme-restored", currentSchemaVersion = 1)
        return assertIs<BackupResult.Success<ImportSummary>>(result).value
    }

    @Test
    fun `identity provider configuration survives a round trip`() {
        val restoredSlug = restore(exportAcme()).newTenantSlug
        val destTenant = destTenants.findBySlug(restoredSlug)!!
        val idp = destIdps.findAllByTenant(destTenant.id).single()

        assertEquals(ProviderKind.OIDC, idp.kind, "an OIDC provider must not come back as oauth2")
        assertEquals("Acme Google", idp.displayName)
        assertEquals("https://accounts.google.com", idp.issuer)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", idp.authorizationEndpoint)
        assertEquals("https://oauth2.googleapis.com/token", idp.tokenEndpoint)
        assertEquals("https://www.googleapis.com/oauth2/v3/certs", idp.jwksUri)
        assertEquals("openid email profile groups", idp.scopes)
        assertTrue(idp.jitEnabled, "just-in-time provisioning must not come back switched off")
        assertEquals(listOf("acme.example.com", "acme.co"), idp.jitAllowedDomains)
        assertTrue(idp.trustEmailClaim)
    }

    @Test
    fun `the provider secret is still never exported, and the provider stays disabled`() {
        val export = exportAcme()
        val serialized =
            kotlinx.serialization.json.Json
                .encodeToString(BackupExportV1.serializer(), export)
        assertFalse(
            serialized.contains("PLAINTEXT-SECRET-MUST-NEVER-LEAK"),
            "widening the provider payload must not have widened it to the secret",
        )

        val destTenant = destTenants.findBySlug(restore(export).newTenantSlug)!!
        val idp = destIdps.findAllByTenant(destTenant.id).single()
        assertFalse(
            idp.enabled,
            "with no secret, an enabled provider fails at the first login instead of at setup",
        )
    }

    @Test
    fun `application audience and launcher configuration survive a round trip`() {
        val destTenant = destTenants.findBySlug(restore(exportAcme()).newTenantSlug)!!
        val app = destApps.findByTenantId(destTenant.id).single()

        assertEquals("https://api.acme.example.com", app.audience)
        assertEquals("https://ingest.acme.example.com", app.launcherUrl)
        assertEquals("https://cdn.acme.example.com/ingest.png", app.iconUrl)
        assertFalse(app.launcherVisible)
        assertEquals(7, app.launcherDisplayOrder)
    }

    @Test
    fun `resource servers survive a round trip`() {
        val summary = restore(exportAcme())
        val destTenant = destTenants.findBySlug(summary.newTenantSlug)!!
        val api = destResourceServers.findByTenantId(destTenant.id).single()

        assertEquals(1, summary.resourceServers)
        assertEquals("https://api.acme.example.com", api.identifier)
        assertEquals("Acme API", api.name)
        assertEquals("Public data API", api.description)
        assertTrue(api.enabled)
        assertEquals(listOf("assistant:ingest", "assistant:keys", "assistant:handoff"), api.scopes)
    }

    @Test
    fun `a restored client keeps its narrowed scopes and does not regain the whole API`() {
        val destTenant = destTenants.findBySlug(restore(exportAcme()).newTenantSlug)!!
        val app = destApps.findByTenantId(destTenant.id).single()
        val api = destResourceServers.findByTenantId(destTenant.id).single()

        assertEquals(
            listOf(api.identifier),
            destResourceServers.listAuthorizedFor(app.id).map { it.identifier },
        )
        // Authorizing a client grants every scope the API declares, so the restore has to narrow
        // it back down afterwards. If that step is skipped or its failure swallowed, the client
        // comes back with all three — a privilege widening produced by a restore.
        assertEquals(
            setOf("assistant:ingest"),
            destResourceServers.findAllowedScopes(app.id)[api.id!!],
        )
    }

    @Test
    fun `a pre-1_26 backup carrying none of these fields still restores`() {
        val export = exportAcme()
        // Exactly the shape an older export has: no resource servers, and every widened field at
        // its default. The defaults are the model's own, so this must restore as it always did.
        val old =
            export.copy(
                resourceServers = emptyList(),
                applications =
                    export.applications.map {
                        it.copy(
                            audience = null,
                            launcherUrl = null,
                            iconUrl = null,
                            launcherVisible = true,
                            launcherDisplayOrder = 0,
                            authorizedResources = emptyList(),
                        )
                    },
                socialProviders =
                    export.socialProviders.map {
                        it.copy(
                            kind = null,
                            displayName = null,
                            issuer = null,
                            authorizationEndpoint = null,
                            tokenEndpoint = null,
                            jwksUri = null,
                            scopes = null,
                            jitEnabled = false,
                            jitAllowedDomains = emptyList(),
                            trustEmailClaim = false,
                        )
                    },
            )

        val summary = restore(old)
        val destTenant = destTenants.findBySlug(summary.newTenantSlug)!!

        assertEquals(0, summary.resourceServers)
        assertTrue(destResourceServers.findByTenantId(destTenant.id).isEmpty())
        val app = destApps.findByTenantId(destTenant.id).single()
        assertNull(app.audience)
        assertNull(app.launcherUrl)
        assertTrue(app.launcherVisible)
        val idp = destIdps.findAllByTenant(destTenant.id).single()
        assertEquals(ProviderKind.OAUTH2, idp.kind, "absent kind falls back to the model default")
        assertEquals("openid email profile", idp.scopes)
    }
}
