package com.kauth.domain.service

import com.kauth.domain.port.TenantProvisioningPort
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceCreationServiceTest {
    private val tenants = FakeTenantRepository()
    private val roles = FakeRoleRepository()
    private val audit = FakeAuditLogPort()
    private val txRunner = FakeTransactionRunner.passThrough()
    private val provisioned = mutableListOf<String>()

    private val service =
        WorkspaceCreationService(
            workspaceSettingsService = WorkspaceSettingsService(tenants, audit),
            roleGroupService =
                RoleGroupService(
                    roleRepository = roles,
                    groupRepository = FakeGroupRepository(),
                    tenantRepository = tenants,
                    userRepository = FakeUserRepository(),
                    applicationRepository = FakeApplicationRepository(),
                    auditLog = audit,
                ),
            tenantProvisioning = TenantProvisioningPort { provisioned += it.slug },
            transactionRunner = txRunner,
        )

    @Test
    fun `a created workspace is provisioned and gets the admin and user roles`() {
        val result = service.create(slug = "acme", displayName = "Acme", issuerUrl = null)

        val tenant = assertIs<AdminResult.Success<com.kauth.domain.model.Tenant>>(result).value
        assertEquals(listOf("acme"), provisioned)
        assertEquals(setOf("admin", "user"), roles.findByTenantId(tenant.id).map { it.name }.toSet())
        assertEquals(1, txRunner.invocations)
    }

    @Test
    fun `the registration flags reach the stored workspace`() {
        service.create(
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            registrationEnabled = false,
            emailVerificationRequired = true,
        )

        val stored = tenants.findBySlug("acme")!!
        assertFalse(stored.registrationEnabled)
        assertTrue(stored.emailVerificationRequired)
    }

    @Test
    fun `an existing slug is a conflict and provisions nothing`() {
        service.create(slug = "acme", displayName = "Acme", issuerUrl = null)
        provisioned.clear()

        val again = service.create(slug = "acme", displayName = "Acme again", issuerUrl = null)

        assertIs<AdminError.Conflict>(assertIs<AdminResult.Failure>(again).error)
        assertTrue(provisioned.isEmpty())
    }

    @Test
    fun `an invalid slug is a validation failure and creates nothing`() {
        val result = service.create(slug = "Not Valid!", displayName = "Acme", issuerUrl = null)

        assertIs<AdminError.Validation>(assertIs<AdminResult.Failure>(result).error)
        assertTrue(tenants.findAll().isEmpty())
        assertTrue(provisioned.isEmpty())
    }
}
