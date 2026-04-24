package com.kauth.domain.service

import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.util.sha256Hex
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for admin-initiated forced password change — `initiateForcedPasswordChange`
 * and `confirmForcedPasswordChange` on [UserSelfServiceService].
 */
class ForcedPasswordChangeTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val evTokenRepo = FakeEmailVerificationTokenRepository()
    private val prTokenRepo = FakePasswordResetTokenRepository()
    private val emailPort = FakeEmailPort()
    private val passwordPolicy = FakePasswordPolicyPort()

    private val svc =
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

    private val tenant =
        Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null)
    private val aliceId = UserId(10)
    private val alice
        get() =
            User(
                id = aliceId,
                tenantId = TenantId(1),
                username = "alice",
                email = "alice@acme",
                fullName = "Alice",
                passwordHash = hasher.hash("old-password"),
                emailVerified = true,
            )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        sessions.clear()
        auditLog.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        passwordPolicy.clear()

        tenants.add(tenant)
        users.add(alice)
    }

    // =========================================================================
    // initiateForcedPasswordChange
    // =========================================================================

    @Test
    fun `initiateForcedPasswordChange stamps CHANGE_PASSWORD and returns a raw token`() {
        val result = svc.initiateForcedPasswordChange(alice)
        assertIs<SelfServiceResult.Success<String>>(result)
        assertTrue(result.value.isNotBlank())

        val refreshed = users.findById(aliceId, tenant.id)
        assertNotNull(refreshed)
        assertTrue(RequiredAction.CHANGE_PASSWORD in refreshed.requiredActions)
    }

    @Test
    fun `initiateForcedPasswordChange writes a TEMP_PASSWORD token hash (never the raw value)`() {
        val result = svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>
        val rawToken = result.value

        val allTokens = prTokenRepo.all()
        val tempTokens = allTokens.filter { it.purpose == TokenPurpose.TEMP_PASSWORD }
        assertEquals(1, tempTokens.size)
        assertEquals(sha256Hex(rawToken), tempTokens.single().tokenHash)
        // Raw token must not appear in the stored hash
        assertFalse(rawToken in tempTokens.single().tokenHash)
    }

    @Test
    fun `initiateForcedPasswordChange deletes prior TEMP_PASSWORD tokens for the same user`() {
        svc.initiateForcedPasswordChange(alice)
        svc.initiateForcedPasswordChange(alice)
        // Second call should have superseded the first.
        val tempTokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.TEMP_PASSWORD }
        assertEquals(1, tempTokens.size)
    }

    // =========================================================================
    // confirmForcedPasswordChange — happy paths
    // =========================================================================

    @Test
    fun `confirmForcedPasswordChange sets the new password and clears CHANGE_PASSWORD`() {
        val rawToken =
            (svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>).value

        val result = svc.confirmForcedPasswordChange(rawToken, "new-password-123", "new-password-123")
        assertIs<SelfServiceResult.Success<Unit>>(result)

        val refreshed = users.findById(aliceId, tenant.id)
        assertNotNull(refreshed)
        assertFalse(RequiredAction.CHANGE_PASSWORD in refreshed.requiredActions)
        assertTrue(hasher.verify("new-password-123", refreshed.passwordHash))
    }

    @Test
    fun `confirmForcedPasswordChange revokes existing sessions`() {
        val rawToken =
            (svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>).value

        svc.confirmForcedPasswordChange(rawToken, "new-password-123", "new-password-123")
        assertTrue(sessions.all().all { it.revokedAt != null })
    }

    @Test
    fun `confirmForcedPasswordChange marks the token used (single-use)`() {
        val rawToken =
            (svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>).value

        svc.confirmForcedPasswordChange(rawToken, "new-password-123", "new-password-123")

        val replay =
            svc.confirmForcedPasswordChange(rawToken, "another-new-pass", "another-new-pass")
        assertIs<SelfServiceResult.Failure>(replay)
    }

    // =========================================================================
    // confirmForcedPasswordChange — purpose guard
    // =========================================================================

    @Test
    fun `confirmForcedPasswordChange rejects PASSWORD_RESET tokens even when the raw value matches`() {
        val rawToken = "shared-raw-token-for-test"
        prTokenRepo.create(
            com.kauth.domain.model.PasswordResetToken(
                userId = aliceId,
                tenantId = tenant.id,
                tokenHash = sha256Hex(rawToken),
                expiresAt = Instant.now().plusSeconds(3600),
                purpose = TokenPurpose.PASSWORD_RESET,
            ),
        )

        val result = svc.confirmForcedPasswordChange(rawToken, "new-pass-123", "new-pass-123")
        assertIs<SelfServiceResult.Failure>(result)

        // Purpose-guarded reject — the reset token is untouched
        val stored = prTokenRepo.all().single { it.purpose == TokenPurpose.PASSWORD_RESET }
        assertNull(stored.usedAt)
    }

    @Test
    fun `confirmForcedPasswordChange rejects mismatched passwords`() {
        val rawToken =
            (svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>).value
        val result = svc.confirmForcedPasswordChange(rawToken, "pass-one", "pass-two")
        assertIs<SelfServiceResult.Failure>(result)
        // User still has CHANGE_PASSWORD because the call failed
        val refreshed = users.findById(aliceId, tenant.id)!!
        assertTrue(RequiredAction.CHANGE_PASSWORD in refreshed.requiredActions)
    }

    @Test
    fun `confirmForcedPasswordChange rejects blank new password`() {
        val rawToken =
            (svc.initiateForcedPasswordChange(alice) as SelfServiceResult.Success<String>).value
        val result = svc.confirmForcedPasswordChange(rawToken, "", "")
        assertIs<SelfServiceResult.Failure>(result)
    }

    @Test
    fun `confirmForcedPasswordChange rejects invalid raw token`() {
        val result = svc.confirmForcedPasswordChange("totally-made-up", "pass-one", "pass-one")
        assertIs<SelfServiceResult.Failure>(result)
    }
}
