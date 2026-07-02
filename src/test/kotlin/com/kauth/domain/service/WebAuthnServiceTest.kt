package com.kauth.domain.service

import com.kauth.adapter.webauthn.AssertionResultData
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeRelyingPartyAdapter
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebAuthnServiceTest {
    private val tenantId = TenantId(1)
    private val userId = UserId(42)
    private val actorId = UserId(1)

    private val tenant =
        Tenant(
            id = tenantId,
            slug = "test",
            displayName = "Test Tenant",
            issuerUrl = null,
            passkeysEnabled = true,
        )

    private val user =
        User(
            id = userId,
            tenantId = tenantId,
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice Example",
            passwordHash = "!",
            enabled = true,
        )

    private lateinit var credRepo: FakeWebAuthnCredentialRepository
    private lateinit var relyingParty: FakeRelyingPartyAdapter
    private lateinit var auditLog: FakeAuditLogPort
    private lateinit var service: WebAuthnService

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)

    @BeforeTest
    fun setup() {
        credRepo = FakeWebAuthnCredentialRepository()
        relyingParty = FakeRelyingPartyAdapter()
        auditLog = FakeAuditLogPort()
        service =
            WebAuthnService(
                credentialRepository = credRepo,
                relyingParty = relyingParty,
                secretKey = "test-secret-key-32-chars-minimum!!",
                auditLog = auditLog,
                clock = fixedClock,
            )
    }

    // -------------------------------------------------------------------------
    // deriveUserHandle
    // -------------------------------------------------------------------------

    @Test
    fun `deriveUserHandle is deterministic`() {
        val h1 = service.deriveUserHandle(tenantId, userId)
        val h2 = service.deriveUserHandle(tenantId, userId)
        assertContentEquals(h1, h2)
    }

    @Test
    fun `deriveUserHandle is exactly 32 bytes`() {
        val handle = service.deriveUserHandle(tenantId, userId)
        assertEquals(32, handle.size)
    }

    @Test
    fun `deriveUserHandle differs by tenant`() {
        val h1 = service.deriveUserHandle(TenantId(1), userId)
        val h2 = service.deriveUserHandle(TenantId(2), userId)
        assertTrue(h1.toList() != h2.toList())
    }

    @Test
    fun `deriveUserHandle differs by user`() {
        val h1 = service.deriveUserHandle(tenantId, UserId(1))
        val h2 = service.deriveUserHandle(tenantId, UserId(2))
        assertTrue(h1.toList() != h2.toList())
    }

    // -------------------------------------------------------------------------
    // startRegistration
    // -------------------------------------------------------------------------

    @Test
    fun `startRegistration returns options with a challenge`() {
        val result = service.startRegistration(user, tenant)
        assertIs<WebAuthnResult.Success<RegistrationOptions>>(result)
        assertTrue(result.value.challenge.isNotBlank())
        assertTrue(result.value.publicKeyOptionsJson.isNotBlank())
    }

    @Test
    fun `startRegistration fails when passkeys disabled`() {
        val disabled = tenant.copy(passkeysEnabled = false)
        val result = service.startRegistration(user, disabled)
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.PasskeysDisabledForTenant, result.error)
    }

    // -------------------------------------------------------------------------
    // finishRegistration
    // -------------------------------------------------------------------------

    @Test
    fun `finishRegistration saves credential and records audit event`() {
        val result =
            service.finishRegistration(
                user = user,
                tenant = tenant,
                creationOptionsJson = FakeRelyingPartyAdapter.CANNED_CREATION_OPTIONS_JSON,
                request = RegistrationFinishRequest(credentialJson = "{}", name = "My MacBook"),
            )
        assertIs<WebAuthnResult.Success<WebAuthnCredential>>(result)
        val saved = result.value
        assertEquals(FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID, saved.credentialId)
        assertEquals("My MacBook", saved.name)
        assertEquals(userId, saved.userId)
        assertEquals(tenantId, saved.tenantId)

        assertTrue(auditLog.hasEvent(AuditEventType.PASSKEY_ENROLLED))
    }

    @Test
    fun `finishRegistration trims name to 64 characters`() {
        val longName = "A".repeat(100)
        val result =
            service.finishRegistration(
                user = user,
                tenant = tenant,
                creationOptionsJson = FakeRelyingPartyAdapter.CANNED_CREATION_OPTIONS_JSON,
                request = RegistrationFinishRequest(credentialJson = "{}", name = longName),
            )
        assertIs<WebAuthnResult.Success<WebAuthnCredential>>(result)
        assertEquals(64, result.value.name.length)
    }

    @Test
    fun `finishRegistration returns VerificationFailed when adapter throws`() {
        relyingParty.throwOnFinishRegistration = "bad attestation"
        val result =
            service.finishRegistration(
                user = user,
                tenant = tenant,
                creationOptionsJson = FakeRelyingPartyAdapter.CANNED_CREATION_OPTIONS_JSON,
                request = RegistrationFinishRequest(credentialJson = "{}", name = "Key"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertIs<WebAuthnError.VerificationFailed>(result.error)
        assertEquals("bad attestation", (result.error as WebAuthnError.VerificationFailed).reason)
    }

    @Test
    fun `finishRegistration fails when passkeys disabled`() {
        val result =
            service.finishRegistration(
                user = user,
                tenant = tenant.copy(passkeysEnabled = false),
                creationOptionsJson = FakeRelyingPartyAdapter.CANNED_CREATION_OPTIONS_JSON,
                request = RegistrationFinishRequest(credentialJson = "{}", name = "Key"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.PasskeysDisabledForTenant, result.error)
    }

    // -------------------------------------------------------------------------
    // startAuthentication
    // -------------------------------------------------------------------------

    @Test
    fun `startAuthentication returns options`() {
        val result = service.startAuthentication(tenant)
        assertIs<WebAuthnResult.Success<AuthenticationOptions>>(result)
        assertTrue(result.value.challenge.isNotBlank())
    }

    @Test
    fun `startAuthentication fails when passkeys disabled`() {
        val result = service.startAuthentication(tenant.copy(passkeysEnabled = false))
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.PasskeysDisabledForTenant, result.error)
    }

    // -------------------------------------------------------------------------
    // finishAuthentication
    // -------------------------------------------------------------------------

    private fun seedCredential(
        signCounter: Long = 0L,
        tenantIdOverride: TenantId = tenantId,
    ): WebAuthnCredential =
        credRepo.save(
            WebAuthnCredential(
                userId = userId,
                tenantId = tenantIdOverride,
                credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
                publicKeyCose = ByteArray(77) { it.toByte() },
                signCounter = signCounter,
                aaguid = null,
                transports = listOf("internal"),
                name = "Test Key",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(fixedClock),
                lastUsedAt = null,
            ),
        )

    @Test
    fun `finishAuthentication succeeds and updates counter`() {
        val seeded = seedCredential(signCounter = 0L)
        relyingParty.queueAssertion(
            AssertionResultData(
                credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
                userHandle = ByteArray(32) { 0x42 },
                newSignCounter = 1L,
                userVerified = true,
            ),
        )

        val result =
            service.finishAuthentication(
                tenant = tenant,
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Success<AuthenticationOutcome>>(result)
        assertEquals(userId, result.value.userId)
        assertTrue(result.value.userVerified)
        assertTrue(auditLog.hasEvent(AuditEventType.PASSKEY_AUTH_SUCCESS))

        // Counter must be updated in repo
        val updated = credRepo.findByCredentialId(FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID)
        assertNotNull(updated)
        assertEquals(1L, updated.signCounter)
    }

    @Test
    fun `finishAuthentication rejects counter replay and revokes credential`() {
        seedCredential(signCounter = 5L)
        relyingParty.queueAssertion(
            AssertionResultData(
                credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
                userHandle = ByteArray(32) { 0x42 },
                newSignCounter = 3L, // lower than stored 5 → replay
                userVerified = true,
            ),
        )

        val result =
            service.finishAuthentication(
                tenant = tenant,
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.CounterReplayDetected, result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.PASSKEY_REPLAY_REJECTED))

        // Credential must be deleted
        assertEquals(null, credRepo.findByCredentialId(FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID))
    }

    @Test
    fun `finishAuthentication rejects when credential not found`() {
        // No credential seeded
        val result =
            service.finishAuthentication(
                tenant = tenant,
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.CredentialNotFound, result.error)
    }

    @Test
    fun `finishAuthentication rejects when tenant mismatch`() {
        credRepo.save(
            WebAuthnCredential(
                userId = userId,
                tenantId = TenantId(99), // different tenant
                credentialId = FakeRelyingPartyAdapter.CANNED_CREDENTIAL_ID,
                publicKeyCose = ByteArray(77),
                signCounter = 0L,
                aaguid = null,
                transports = emptyList(),
                name = "Key",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(fixedClock),
                lastUsedAt = null,
            ),
        )

        val result =
            service.finishAuthentication(
                tenant = tenant, // TenantId(1) vs stored TenantId(99)
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.TenantMismatch, result.error)
    }

    @Test
    fun `finishAuthentication returns VerificationFailed when adapter throws`() {
        seedCredential()
        relyingParty.throwOnFinishAssertion = "signature invalid"
        val result =
            service.finishAuthentication(
                tenant = tenant,
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertIs<WebAuthnError.VerificationFailed>(result.error)
    }

    @Test
    fun `finishAuthentication fails when passkeys disabled`() {
        val result =
            service.finishAuthentication(
                tenant = tenant.copy(passkeysEnabled = false),
                assertionRequestJson = FakeRelyingPartyAdapter.CANNED_ASSERTION_REQUEST_JSON,
                request = AuthenticationFinishRequest(credentialJson = "{}"),
            )
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.PasskeysDisabledForTenant, result.error)
    }

    // -------------------------------------------------------------------------
    // listForUser
    // -------------------------------------------------------------------------

    @Test
    fun `listForUser returns only credentials for the given user and tenant`() {
        seedCredential()
        credRepo.save(
            WebAuthnCredential(
                userId = UserId(99),
                tenantId = tenantId,
                credentialId = "other-cred",
                publicKeyCose = ByteArray(1),
                signCounter = 0,
                aaguid = null,
                transports = emptyList(),
                name = "Other User Key",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(fixedClock),
                lastUsedAt = null,
            ),
        )
        val list = service.listForUser(userId, tenantId)
        assertEquals(1, list.size)
        assertEquals(userId, list.first().userId)
    }

    // -------------------------------------------------------------------------
    // rename
    // -------------------------------------------------------------------------

    @Test
    fun `rename succeeds for own credential`() {
        val saved = seedCredential()
        val result = service.rename(userId, saved.id!!, "YubiKey 5")
        assertIs<WebAuthnResult.Success<Unit>>(result)
        assertEquals("YubiKey 5", credRepo.findById(saved.id!!)?.name)
    }

    @Test
    fun `rename returns CredentialNotFound for non-existent credential`() {
        val result = service.rename(userId, 9999L, "Ghost Key")
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.CredentialNotFound, result.error)
    }

    @Test
    fun `rename returns VerificationFailed for blank name`() {
        val saved = seedCredential()
        val result = service.rename(userId, saved.id!!, "   ")
        assertIs<WebAuthnResult.Failure>(result)
        assertIs<WebAuthnError.VerificationFailed>(result.error)
    }

    // -------------------------------------------------------------------------
    // revoke
    // -------------------------------------------------------------------------

    @Test
    fun `revoke succeeds and records audit event`() {
        val saved = seedCredential()
        val result = service.revoke(userId, saved.id!!, tenantId)
        assertIs<WebAuthnResult.Success<Unit>>(result)
        assertEquals(null, credRepo.findById(saved.id!!))
        assertTrue(auditLog.hasEvent(AuditEventType.PASSKEY_REVOKED))
    }

    @Test
    fun `revoke returns CredentialNotFound cross-user`() {
        val saved = seedCredential()
        val result = service.revoke(UserId(99), saved.id!!, tenantId)
        assertIs<WebAuthnResult.Failure>(result)
        assertEquals(WebAuthnError.CredentialNotFound, result.error)
    }

    // -------------------------------------------------------------------------
    // adminResetAll
    // -------------------------------------------------------------------------

    @Test
    fun `adminResetAll deletes all credentials and returns count`() {
        seedCredential()
        // Seed a second credential for the same user
        credRepo.save(
            WebAuthnCredential(
                userId = userId,
                tenantId = tenantId,
                credentialId = "second-cred-id",
                publicKeyCose = ByteArray(1),
                signCounter = 0,
                aaguid = null,
                transports = emptyList(),
                name = "Second Key",
                backupEligible = false,
                backupState = false,
                createdAt = Instant.now(fixedClock),
                lastUsedAt = null,
            ),
        )

        val result = service.adminResetAll(tenantId, userId, actorId)
        assertIs<WebAuthnResult.Success<Int>>(result)
        assertEquals(2, result.value)
        assertEquals(emptyList(), credRepo.findByUserId(userId, tenantId))
        assertTrue(auditLog.hasEvent(AuditEventType.PASSKEY_ADMIN_RESET_ALL))
    }
}
