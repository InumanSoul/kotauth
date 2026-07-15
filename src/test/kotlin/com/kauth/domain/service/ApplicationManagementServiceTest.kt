package com.kauth.domain.service

import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeTenantRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            )

        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("my-app", result.value.clientId)
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
        applicationRepo.create(tenantId, "my-app", "Existing App", null, "public", listOf("https://example.com/cb"))

        val result =
            service.createApplication(
                tenantId = tenantId,
                clientId = "my-app",
                name = "My App",
                description = null,
                accessType = "public",
                redirectUris = listOf("https://example.com/callback"),
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
            )

        assertIs<AdminResult.Success<Application>>(result)
        assertEquals(1, auditLog.countOf(AuditEventType.ADMIN_CLIENT_CREATED))
        val event = auditLog.events.first { it.eventType == AuditEventType.ADMIN_CLIENT_CREATED }
        assertEquals(tenantId, event.tenantId)
        assertEquals(result.value.id, event.clientId)
        assertEquals("my-app", event.details["clientId"])
    }
}
