package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationManagementServiceTest {
    private lateinit var applicationRepo: FakeApplicationRepository
    private lateinit var tenantRepo: FakeTenantRepository
    private lateinit var auditLog: FakeAuditLogPort
    private lateinit var passwordHasher: FakePasswordHasher
    private lateinit var service: ApplicationManagementService

    private val tenantId = TenantId(1)

    @BeforeTest
    fun setup() {
        applicationRepo = FakeApplicationRepository()
        tenantRepo = FakeTenantRepository()
        auditLog = FakeAuditLogPort()
        passwordHasher = FakePasswordHasher()
        service = ApplicationManagementService(applicationRepo, tenantRepo, passwordHasher, auditLog)
    }

    @Test
    fun `createApplication returns Success and persists when inputs are valid`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = "A test app",
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
        assertEquals("my-app", result.value.application.clientId)
        val persisted = applicationRepo.findByClientId(tenantId, "my-app")
        assertEquals("My App", persisted?.name)
    }

    @Test
    fun `createApplication rejects blank clientId with 'Client ID is required'`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals("Client ID is required.", result.error.message)
    }

    @Test
    fun `createApplication rejects clientId with uppercase or symbols`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "My_App!",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals(
            "Client ID may only contain lowercase letters, numbers, and hyphens.",
            result.error.message,
        )
    }

    @Test
    fun `createApplication rejects blank name`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals("Name is required.", result.error.message)
    }

    @Test
    fun `createApplication rejects empty redirectUris`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals(
            "At least one redirect URI is required. The authorization code flow needs a registered URI to bind to.",
            result.error.message,
        )
    }

    @Test
    fun `createApplication rejects duplicate clientId with Conflict error`() {
        applicationRepo.create(
            tenantId = tenantId,
            clientId = "my-app",
            name = "Existing App",
            description = null,
            accessType = "public",
            redirectUris = listOf("https://example.com/cb"),
            grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
            clientSecretHash = null,
            audience = null,
        )

        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
        assertEquals("Client ID 'my-app' already exists.", result.error.message)
    }

    @Test
    fun `createApplication emits ADMIN_CLIENT_CREATED audit event on success`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.fromValue("public")),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
        assertEquals(1, auditLog.countOf(AuditEventType.ADMIN_CLIENT_CREATED))
        val event = auditLog.events.first { it.eventType == AuditEventType.ADMIN_CLIENT_CREATED }
        assertEquals(tenantId, event.tenantId)
        assertEquals(result.value.application.id, event.clientId)
        assertEquals("my-app", event.details["clientId"])
    }

    @Test
    fun `deleteApplication returns Success and calls softDelete`() {
        val app =
            applicationRepo.create(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
                clientSecretHash = null,
                audience = null,
            )

        val result = service.deleteApplication(app.id, tenantId)

        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(null, applicationRepo.findById(app.id))
    }

    @Test
    fun `deleteApplication returns NotFound for unknown appId`() {
        val result = service.deleteApplication(ApplicationId(99999), tenantId)

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `deleteApplication returns NotFound when appId belongs to a different tenant`() {
        val app =
            applicationRepo.create(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
                clientSecretHash = null,
                audience = null,
            )
        val otherTenantId = TenantId(2)

        val result = service.deleteApplication(app.id, otherTenantId)

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `deleteApplication emits ADMIN_CLIENT_DELETED audit event on success`() {
        val app =
            applicationRepo.create(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
                clientSecretHash = null,
                audience = null,
            )

        val result = service.deleteApplication(app.id, tenantId)

        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, auditLog.countOf(AuditEventType.ADMIN_CLIENT_DELETED))
        val event = auditLog.events.first { it.eventType == AuditEventType.ADMIN_CLIENT_DELETED }
        assertEquals(tenantId, event.tenantId)
        assertEquals(app.id, event.clientId)
        assertEquals("my-app", event.details["clientId"])
    }

    @Test
    fun `createApplication allows an M2M client with no redirect URIs`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "erp-caller",
                name = "ERP Caller",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
        assertEquals(emptyList(), result.value.application.redirectUris)
    }

    @Test
    fun `createApplication still requires a redirect URI for the authorization code grant`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "web-app",
                name = "Web App",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createApplication rejects client credentials on a public client`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "spa",
                name = "SPA",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE, GrantType.CLIENT_CREDENTIALS),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createApplication rejects an empty grant set on a confidential client`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "no-grants",
                name = "No Grants",
                description = null,
                accessType = "confidential",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = emptySet(),
            )

        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `createApplication accepts an empty grant set on a bearer only client`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "api-only",
                name = "API Only",
                description = null,
                accessType = "bearer_only",
                redirectUris = emptyList(),
                grantTypes = emptySet(),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
    }

    @Test
    fun `createApplication rejects a bearer only client with grants selected`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "bearer-with-grants",
                name = "Bearer With Grants",
                description = null,
                accessType = "bearer_only",
                redirectUris = emptyList(),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
            )

        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createApplication issues a client secret for a confidential client`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "erp-caller",
                name = "ERP Caller",
                description = null,
                accessType = "confidential",
                redirectUris = emptyList(),
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
        val secret = result.value.plaintextSecret
        assertNotNull(secret)
        assertTrue(secret.isNotBlank())
        assertNotNull(applicationRepo.findClientSecretHash(result.value.application.id))
    }

    @Test
    fun `createApplication issues no secret for a public client`() {
        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "spa",
                name = "SPA",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/cb"),
                grantTypes = setOf(GrantType.AUTHORIZATION_CODE),
            )

        assertIs<AdminResult.Success<CreatedApplication>>(result)
        assertNull(result.value.plaintextSecret)
        assertNull(applicationRepo.findClientSecretHash(result.value.application.id))
    }
}
