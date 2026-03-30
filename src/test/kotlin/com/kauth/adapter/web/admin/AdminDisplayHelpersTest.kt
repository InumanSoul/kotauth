package com.kauth.adapter.web.admin

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.service.AdminService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordPolicyPort
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [resolveUsernames] and [resolveClientNames] display helpers.
 *
 * Covers: known IDs resolve to display names, unknown IDs fall back to the
 * numeric ID string, and empty input returns an empty map.
 */
class AdminDisplayHelpersTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val apps = FakeApplicationRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val sessions = FakeSessionRepository()
    private val passwordPolicy = FakePasswordPolicyPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()

    private val selfService =
        UserSelfServiceService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = evTokenRepo,
            prTokenRepo = prTokenRepo,
            emailPort = emailPort,
            passwordPolicy = passwordPolicy,
            emailScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private val adminService =
        AdminService(
            tenantRepository = tenants,
            userRepository = users,
            applicationRepository = apps,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessions,
            selfServiceService = selfService,
            passwordPolicy = passwordPolicy,
        )

    private val tenantId = TenantId(1)

    private val alice =
        User(
            id = UserId(10),
            tenantId = tenantId,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice Test",
            passwordHash = hasher.hash("pass"),
            enabled = true,
        )

    private val testApp =
        Application(
            id = ApplicationId(100),
            tenantId = tenantId,
            clientId = "my-app",
            name = "My App",
            description = "Test app",
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = listOf("http://localhost/callback"),
        )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        apps.clear()
        auditLog.clear()
        sessions.clear()
        passwordPolicy.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()

        tenants.add(
            Tenant(
                id = tenantId,
                slug = "acme",
                displayName = "Acme Corp",
                issuerUrl = null,
                securityConfig = SecurityConfig(passwordMinLength = 8),
            ),
        )
        users.add(alice)
        apps.add(testApp)
    }

    // =========================================================================
    // resolveUsernames
    // =========================================================================

    @Test
    fun `resolveUsernames - returns username for known user`() {
        val result = resolveUsernames(listOf(UserId(10)), tenantId, adminService)
        assertEquals("alice", result[UserId(10)])
    }

    @Test
    fun `resolveUsernames - falls back to ID string for unknown user`() {
        val result = resolveUsernames(listOf(UserId(999)), tenantId, adminService)
        assertEquals("999", result[UserId(999)])
    }

    @Test
    fun `resolveUsernames - returns empty map for empty input`() {
        val result = resolveUsernames(emptyList(), tenantId, adminService)
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // resolveClientNames
    // =========================================================================

    @Test
    fun `resolveClientNames - returns app name for known client`() {
        val result = resolveClientNames(listOf(ApplicationId(100)), apps)
        assertEquals("My App", result[ApplicationId(100)])
    }

    @Test
    fun `resolveClientNames - falls back to ID string for unknown client`() {
        val result = resolveClientNames(listOf(ApplicationId(999)), apps)
        assertEquals("999", result[ApplicationId(999)])
    }

    @Test
    fun `resolveClientNames - returns empty map for empty input`() {
        val result = resolveClientNames(emptyList(), apps)
        assertTrue(result.isEmpty())
    }
}
