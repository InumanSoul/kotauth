package com.kauth.adapter.web.auth

import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.Role
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.service.MfaService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.PortalClientProvisioning
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MfaGateTest {
    private val mfaRepository = FakeMfaRepository()
    private val roles = FakeRoleRepository()
    private val gate =
        MfaGate(
            MfaService(
                mfaRepository,
                FakeUserRepository(),
                FakeTenantRepository(),
                FakePasswordHasher(),
                FakeAuditLogPort(),
            ),
            roles,
        )
    private val alice = UserId(10)

    private fun workspace(policy: String) =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            securityConfig = SecurityConfig(mfaPolicy = policy),
        )

    private fun enrollAlice() {
        mfaRepository.saveEnrollment(
            MfaEnrollment(userId = alice, tenantId = TenantId(1), secret = "secret", verified = true),
        )
    }

    private fun makeAliceAdmin() {
        val admin = roles.add(Role(tenantId = TenantId(1), name = "admin"))
        roles.assignRoleToUser(alice, admin.id!!)
    }

    @BeforeTest
    fun reset() {
        mfaRepository.clear()
        roles.clear()
    }

    @Test
    fun `an enrolled user is challenged under every policy`() {
        enrollAlice()
        listOf("optional", "required", "required_admins").forEach { policy ->
            assertEquals(MfaStep.CHALLENGE, gate.stepFor(alice, workspace(policy), "spa-app"), policy)
        }
    }

    @Test
    fun `an enrolled user is challenged on the portal too`() {
        enrollAlice()
        assertEquals(
            MfaStep.CHALLENGE,
            gate.stepFor(alice, workspace("optional"), PortalClientProvisioning.PORTAL_CLIENT_ID),
        )
    }

    @Test
    fun `an unverified enrollment does not count`() {
        mfaRepository.saveEnrollment(
            MfaEnrollment(userId = alice, tenantId = TenantId(1), secret = "secret", verified = false),
        )
        assertEquals(MfaStep.PROCEED, gate.stepFor(alice, workspace("optional"), "spa-app"))
        assertEquals(MfaStep.ENROLLMENT_REQUIRED, gate.stepFor(alice, workspace("required"), "spa-app"))
    }

    @Test
    fun `an unenrolled user proceeds when MFA is optional`() {
        assertEquals(MfaStep.PROCEED, gate.stepFor(alice, workspace("optional"), "spa-app"))
    }

    @Test
    fun `an unenrolled user is refused when MFA is required`() {
        assertEquals(MfaStep.ENROLLMENT_REQUIRED, gate.stepFor(alice, workspace("required"), "spa-app"))
    }

    @Test
    fun `an unenrolled user may still reach the portal to enroll`() {
        assertEquals(
            MfaStep.PROCEED,
            gate.stepFor(alice, workspace("required"), PortalClientProvisioning.PORTAL_CLIENT_ID),
        )
    }

    @Test
    fun `required_admins refuses an unenrolled admin and lets anyone else through`() {
        assertEquals(MfaStep.PROCEED, gate.stepFor(alice, workspace("required_admins"), "spa-app"))
        makeAliceAdmin()
        assertEquals(MfaStep.ENROLLMENT_REQUIRED, gate.stepFor(alice, workspace("required_admins"), "spa-app"))
    }

    @Test
    fun `without an MFA service nothing is asked`() {
        assertEquals(MfaStep.PROCEED, MfaGate(null, null).stepFor(alice, workspace("required"), "spa-app"))
    }
}
