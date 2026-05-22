package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.SecurityConfig
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for magic-link (passwordless) sign-in — `initiateMagicLink` and
 * `consumeMagicLink` on [UserSelfServiceService].
 */
class MagicLinkTest {
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

    /** SMTP-ready + magic-link-enabled tenant used in most tests. */
    private val enabledTenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            smtpUsername = "noreply@acme.com",
            smtpPassword = "secret",
            smtpFromAddress = "noreply@acme.com",
            smtpFromName = "Acme",
            securityConfig = SecurityConfig(magicLinkEnabled = true),
        )

    /** Magic-link disabled, SMTP configured — feature-off silent path. */
    private val disabledTenant =
        enabledTenant.copy(
            id = TenantId(2),
            slug = "disabled",
            securityConfig = SecurityConfig(magicLinkEnabled = false),
        )

    /** Magic-link enabled, SMTP NOT configured — SMTP-off silent path. */
    private val noSmtpTenant =
        Tenant(
            id = TenantId(3),
            slug = "nosmtp",
            displayName = "No SMTP",
            issuerUrl = null,
            securityConfig = SecurityConfig(magicLinkEnabled = true),
        )

    private val aliceId = UserId(10)
    private val alice
        get() =
            User(
                id = aliceId,
                tenantId = TenantId(1),
                username = "alice",
                email = "alice@acme.com",
                fullName = "Alice",
                passwordHash = hasher.hash("existing-pass"),
                emailVerified = false,
            )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        sessions.clear()
        auditLog.clear()
        evTokenRepo.clear()
        prTokenRepo.clear()
        emailPort.clear()
        passwordPolicy.clear()

        tenants.add(enabledTenant)
        tenants.add(disabledTenant)
        tenants.add(noSmtpTenant)
        users.add(alice)
    }

    // =========================================================================
    // initiateMagicLink — happy path
    // =========================================================================

    @Test
    fun `initiate writes a MAGIC_LINK token and sends an email`() {
        val result =
            svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", ipAddress = null)
        assertIs<SelfServiceResult.Success<Unit>>(result)

        val tokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.MAGIC_LINK }
        assertEquals(1, tokens.size)
        assertEquals(1, emailPort.sent.size)
        assertEquals("alice@acme.com", emailPort.sent.single().to)
        assertEquals("magic_link", emailPort.sent.single().type)
    }

    @Test
    fun `initiate deletes prior MAGIC_LINK tokens for the same user`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val tokens = prTokenRepo.all().filter { it.purpose == TokenPurpose.MAGIC_LINK }
        assertEquals(1, tokens.size, "Second request must supersede the first")
    }

    @Test
    fun `initiate honors the tenant's configured magic-link TTL`() {
        tenants.clear()
        tenants.add(
            enabledTenant.copy(
                securityConfig = SecurityConfig(magicLinkEnabled = true, magicLinkTokenTtlMinutes = 45),
            ),
        )

        val before = java.time.Instant.now()
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val token = prTokenRepo.all().single { it.purpose == TokenPurpose.MAGIC_LINK }

        val ttlSeconds =
            java.time.Duration
                .between(before, token.expiresAt)
                .seconds
        assertTrue(ttlSeconds in 2640..2760, "Expiry should be ~45 minutes out, was ${ttlSeconds}s")
    }

    @Test
    fun `initiate defaults to a 15-minute TTL when not configured`() {
        val before = java.time.Instant.now()
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val token = prTokenRepo.all().single { it.purpose == TokenPurpose.MAGIC_LINK }

        val ttlSeconds =
            java.time.Duration
                .between(before, token.expiresAt)
                .seconds
        assertTrue(ttlSeconds in 840..960, "Expiry should be ~15 minutes out, was ${ttlSeconds}s")
    }

    @Test
    fun `initiate records the TTL on the MAGIC_LINK_REQUESTED audit event`() {
        tenants.clear()
        tenants.add(
            enabledTenant.copy(
                securityConfig = SecurityConfig(magicLinkEnabled = true, magicLinkTokenTtlMinutes = 30),
            ),
        )

        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)

        val event = auditLog.events.single { it.eventType == AuditEventType.MAGIC_LINK_REQUESTED }
        assertEquals("30", event.details["ttl_minutes"])
    }

    @Test
    fun `initiate token has ~15 minute TTL`() {
        val before = Instant.now()
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val token = prTokenRepo.all().single { it.purpose == TokenPurpose.MAGIC_LINK }
        val ttlSeconds = token.expiresAt.epochSecond - before.epochSecond
        assertTrue(ttlSeconds in 890..910, "TTL should be ~900s, was $ttlSeconds")
    }

    // =========================================================================
    // initiateMagicLink — enumeration protection (always Success, silent no-op)
    // =========================================================================

    @Test
    fun `initiate returns Success for unknown tenant and sends no email`() {
        val result = svc.initiateMagicLink("alice@acme.com", "no-such-tenant", "url", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    @Test
    fun `initiate returns Success and sends no email when tenant has feature disabled`() {
        val result = svc.initiateMagicLink("alice@acme.com", "disabled", "url", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    @Test
    fun `initiate returns Success and sends no email when SMTP is not configured`() {
        val result = svc.initiateMagicLink("alice@acme.com", "nosmtp", "url", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    @Test
    fun `initiate returns Success and sends no email for unknown user`() {
        val result = svc.initiateMagicLink("nobody@acme.com", "acme", "url", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    @Test
    fun `initiate returns Success and sends no email for disabled user`() {
        users.clear()
        users.add(alice.copy(enabled = false))
        val result = svc.initiateMagicLink("alice@acme.com", "acme", "url", null)
        assertIs<SelfServiceResult.Success<Unit>>(result)
        assertTrue(emailPort.sent.isEmpty())
    }

    // =========================================================================
    // consumeMagicLink — happy path
    // =========================================================================

    @Test
    fun `consume returns the user and marks the token used`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Success<User>>(result)
        assertEquals("alice", result.value.username)
        assertTrue(prTokenRepo.all().single { it.purpose == TokenPurpose.MAGIC_LINK }.usedAt != null)
    }

    @Test
    fun `consume sets emailVerified=true`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()

        svc.consumeMagicLink(rawToken)
        val refreshed = users.findById(aliceId, TenantId(1))!!
        assertTrue(refreshed.emailVerified)
    }

    @Test
    fun `consume clears SET_PASSWORD required action - invite bypass works`() {
        users.clear()
        users.add(alice.copy(requiredActions = setOf(RequiredAction.SET_PASSWORD)))
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Success<User>>(result)
        val refreshed = users.findById(aliceId, TenantId(1))!!
        assertFalse(RequiredAction.SET_PASSWORD in refreshed.requiredActions)
    }

    @Test
    fun `consume marks token single-use (replay rejected)`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()

        svc.consumeMagicLink(rawToken)
        val replay = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Failure>(replay)
    }

    // =========================================================================
    // consumeMagicLink — failure paths
    // =========================================================================

    @Test
    fun `consume rejects unknown token`() {
        val result = svc.consumeMagicLink("totally-made-up-token")
        assertIs<SelfServiceResult.Failure>(result)
    }

    @Test
    fun `consume rejects PASSWORD_RESET tokens even with matching raw value`() {
        val rawToken = "shared-raw-for-purpose-guard"
        prTokenRepo.create(
            PasswordResetToken(
                userId = aliceId,
                tenantId = TenantId(1),
                tokenHash = sha256Hex(rawToken),
                expiresAt = Instant.now().plusSeconds(900),
                purpose = TokenPurpose.PASSWORD_RESET,
            ),
        )

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Failure>(result)
        // Reset token untouched — purpose guard ran before any state change
        val stored = prTokenRepo.all().single { it.purpose == TokenPurpose.PASSWORD_RESET }
        assertNull(stored.usedAt)
    }

    @Test
    fun `consume rejects expired token`() {
        val rawToken = "expired-token"
        prTokenRepo.create(
            PasswordResetToken(
                userId = aliceId,
                tenantId = TenantId(1),
                tokenHash = sha256Hex(rawToken),
                expiresAt = Instant.now().minusSeconds(60),
                purpose = TokenPurpose.MAGIC_LINK,
            ),
        )

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Failure>(result)
    }

    @Test
    fun `consume rejects disabled user`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()
        users.update(alice.copy(enabled = false))

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Failure>(result)
    }

    @Test
    fun `consume blocks when user has CHANGE_PASSWORD required action`() {
        svc.initiateMagicLink("alice@acme.com", "acme", "http://localhost:8080", null)
        val rawToken = extractRawTokenFromSentEmail()
        users.update(alice.copy(requiredActions = setOf(RequiredAction.CHANGE_PASSWORD)))

        val result = svc.consumeMagicLink(rawToken)
        assertIs<SelfServiceResult.Failure>(result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the raw token from the magic-link URL captured by FakeEmailPort.
     * Format: `$baseUrl/t/{slug}/magic-link/consume?token=$rawToken`
     */
    private fun extractRawTokenFromSentEmail(): String {
        val url = emailPort.sent.single { it.type == "magic_link" }.url
        return url.substringAfter("?token=")
    }
}
