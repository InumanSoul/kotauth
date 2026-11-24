package com.kauth.domain.service

import com.kauth.domain.model.FactorChange
import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeMfaRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import com.kauth.infrastructure.TotpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The user hears about every change to their sign-in factors, and only about real ones. */
class FactorChangeNotificationTest {
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val emails = FakeEmailPort()
    private val mfaRepo = FakeMfaRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val credRepo = FakeWebAuthnCredentialRepository()

    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            passkeysEnabled = true,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpFromAddress = "noreply@acme.com",
        )

    private val alice =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@acme.com",
            fullName = "Alice",
            passwordHash = "hashed:pw",
            enabled = true,
        )

    private val notifier = FactorChangeNotifier(users, tenants, emails, CoroutineScope(Dispatchers.Unconfined))
    private val mfa = MfaService(mfaRepo, users, tenants, hasher, auditLog, factorChangeNotifier = notifier)
    private val passkeys =
        WebAuthnService(
            credentialRepository = credRepo,
            relyingParty = FakeRelyingPartyAdapter(),
            secretKey = "test-secret-key-32-chars-minimum!!",
            auditLog = auditLog,
            userRepository = users,
            tenantRepository = tenants,
            factorChangeNotifier = notifier,
        )

    @BeforeTest
    fun setUp() {
        users.clear()
        tenants.clear()
        emails.clear()
        mfaRepo.clear()
        users.add(alice)
        tenants.add(acme)
    }

    private fun changes() = emails.sent.filter { it.type == "factor_changed" }.map { it.change }

    private fun enrollTotp(): String {
        val begun = mfa.beginEnrollment(alice.id!!, acme.id, "Acme") as MfaResult.Success<EnrollmentResponse>
        assertIs<MfaResult.Success<*>>(
            mfa.verifyEnrollment(alice.id!!, TotpUtil.generateCode(begun.value.enrollment.secret)),
        )
        return begun.value.recoveryCodes.first()
    }

    private fun seedPasskey(): WebAuthnCredential =
        credRepo.save(
            WebAuthnCredential(
                userId = alice.id!!,
                tenantId = acme.id,
                credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
                publicKeyCose = ByteArray(77),
                signCounter = 0,
                aaguid = null,
                transports = emptyList(),
                name = "Key",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
            ),
        )

    @Test
    fun `turning on TOTP sends one email to the user`() {
        enrollTotp()

        assertEquals(listOf(FactorChange.TOTP_ADDED), changes())
        assertEquals("alice@acme.com", emails.sent.single().to)
    }

    @Test
    fun `a wrong enrollment code sends nothing`() {
        mfa.beginEnrollment(alice.id!!, acme.id, "Acme")

        mfa.verifyEnrollment(alice.id!!, "000000")

        assertTrue(changes().isEmpty())
    }

    @Test
    fun `using a recovery code says how many are left`() {
        val code = enrollTotp()
        emails.clear()

        mfa.verifyRecoveryCode(alice.id!!, code)

        val sent = emails.sent.single()
        assertEquals(FactorChange.RECOVERY_CODE_USED, sent.change)
        assertEquals(MfaService.RECOVERY_CODE_COUNT - 1, sent.remainingRecoveryCodes)
    }

    @Test
    fun `a wrong recovery code sends nothing`() {
        enrollTotp()
        emails.clear()

        mfa.verifyRecoveryCode(alice.id!!, "not-a-code")

        assertTrue(changes().isEmpty())
    }

    @Test
    fun `an admin reset of TOTP is reported as the admin's`() {
        enrollTotp()
        emails.clear()

        mfa.disableMfa(alice.id!!, acme.id, byAdmin = true)

        assertEquals(listOf(FactorChange.TOTP_RESET_BY_ADMIN), changes())
    }

    @Test
    fun `removing TOTP the user removes themselves is reported as theirs`() {
        enrollTotp()
        emails.clear()

        mfa.disableMfa(alice.id!!, acme.id)

        assertEquals(listOf(FactorChange.TOTP_REMOVED), changes())
    }

    @Test
    fun `resetting a user who never finished enrolling sends nothing`() {
        mfaRepo.saveEnrollment(MfaEnrollment(userId = alice.id!!, tenantId = acme.id, secret = "secret"))

        mfa.disableMfa(alice.id!!, acme.id, byAdmin = true)

        assertTrue(changes().isEmpty())
    }

    @Test
    fun `adding a passkey sends an email`() {
        passkeys.finishRegistration(
            user = alice,
            tenant = acme,
            creationOptionsJson = FakeRelyingPartyAdapter.CANNED_CREATION_OPTIONS_JSON,
            request = RegistrationFinishRequest(credentialJson = "{}", name = "Laptop"),
        )

        assertEquals(listOf(FactorChange.PASSKEY_ADDED), changes())
    }

    @Test
    fun `removing a passkey says who removed it`() {
        passkeys.revoke(alice.id!!, seedPasskey().id!!, acme.id)
        passkeys.revoke(alice.id!!, seedPasskey().id!!, acme.id, byAdmin = true)

        assertEquals(listOf(FactorChange.PASSKEY_REMOVED, FactorChange.PASSKEY_REMOVED_BY_ADMIN), changes())
    }

    @Test
    fun `an admin reset of passkeys emails only when something was removed`() {
        passkeys.adminResetAll(acme.id, alice.id!!, actorId = UserId(1))
        assertTrue(changes().isEmpty())

        seedPasskey()
        passkeys.adminResetAll(acme.id, alice.id!!, actorId = UserId(1))

        assertEquals(listOf(FactorChange.PASSKEYS_RESET_BY_ADMIN), changes())
    }

    @Test
    fun `a workspace without SMTP sends nothing`() {
        tenants.update(acme.copy(smtpEnabled = false))

        enrollTotp()

        assertTrue(emails.sent.isEmpty())
    }

    @Test
    fun `a failing mail server does not fail the change`() {
        emails.shouldFail = true

        enrollTotp()

        assertTrue(mfa.shouldChallengeMfa(alice.id!!))
    }
}
