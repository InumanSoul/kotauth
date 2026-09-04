package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AuthService].
 *
 * All I/O is replaced by in-memory fakes — no DB, no HTTP, no BCrypt.
 * Each test group is independent: @BeforeTest resets all fakes.
 */
class AuthServiceTest {
    // -------------------------------------------------------------------------
    // Fakes (reset before each test)
    // -------------------------------------------------------------------------

    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val sessions = FakeSessionRepository()
    private val tokens = FakeTokenPort()

    private val svc =
        AuthService(
            userRepository = users,
            tenantRepository = tenants,
            tokenPort = tokens,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessions,
            identifierResolver = UserIdentifierResolver(users),
        )

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private val testTenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            registrationEnabled = true,
        )

    private val activeUser get() =
        User(
            id = UserId(10),
            tenantId = TenantId(1),
            username = "alice",
            email = "alice@example.com",
            fullName = "Alice Test",
            passwordHash = hasher.hash("correct-pass"),
            enabled = true,
        )

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        auditLog.clear()
        sessions.clear()
        tenants.add(testTenant)
        users.add(activeUser)
    }

    // =========================================================================
    // authenticate()
    // =========================================================================

    @Test
    fun `authenticate returns TenantNotFound for unknown slug`() {
        val result = svc.authenticate("no-such-tenant", "alice", "correct-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.TenantNotFound>(result.error)
    }

    @Test
    fun `authenticate returns InvalidCredentials for blank username`() {
        val result = svc.authenticate("acme", "", "correct-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `authenticate returns InvalidCredentials for blank password`() {
        val result = svc.authenticate("acme", "alice", "")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `authenticate returns InvalidCredentials for unknown user - no user enumeration`() {
        val result = svc.authenticate("acme", "does-not-exist", "correct-pass")
        // Must be InvalidCredentials, not a "user not found" variant
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
        // Audit event must still be recorded
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate returns InvalidCredentials for disabled user - no distinguishing error`() {
        users.clear()
        users.add(activeUser.copy(enabled = false))

        val result = svc.authenticate("acme", "alice", "correct-pass")

        // Disabled users must be indistinguishable from wrong-password to prevent enumeration
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `authenticate returns InvalidCredentials for wrong password`() {
        val result = svc.authenticate("acme", "alice", "wrong-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate records LOGIN_SUCCESS audit event on correct credentials`() {
        val result = svc.authenticate("acme", "alice", "correct-pass")
        assertIs<AuthResult.Success<User>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_SUCCESS))
        assertEquals(0, auditLog.countOf(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate returns Success with correct user on valid credentials`() {
        val result = svc.authenticate("acme", "alice", "correct-pass")
        assertIs<AuthResult.Success<User>>(result)
        assertEquals("alice", result.value.username)
        assertEquals(TenantId(1), result.value.tenantId)
    }

    // -------------------------------------------------------------------------
    // Password expiry cases
    // -------------------------------------------------------------------------

    @Test
    fun `authenticate returns PasswordExpired when policy is set and password age exceeds limit`() {
        val tenant = testTenant.copy(securityConfig = testTenant.securityConfig.copy(passwordMaxAgeDays = 90))
        tenants.clear()
        tenants.add(tenant)

        val expiredUser =
            activeUser.copy(
                // last change was 91 days ago — exceeds the 90-day policy
                lastPasswordChangeAt = Instant.now().minusSeconds(91L * 86_400),
            )
        users.clear()
        users.add(expiredUser)

        val result = svc.authenticate("acme", "alice", "correct-pass")

        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.PasswordExpired>(result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate returns Success when password age is within policy limit`() {
        val tenant = testTenant.copy(securityConfig = testTenant.securityConfig.copy(passwordMaxAgeDays = 90))
        tenants.clear()
        tenants.add(tenant)

        val freshUser =
            activeUser.copy(
                // last change was 30 days ago — within the 90-day policy
                lastPasswordChangeAt = Instant.now().minusSeconds(30L * 86_400),
            )
        users.clear()
        users.add(freshUser)

        val result = svc.authenticate("acme", "alice", "correct-pass")

        assertIs<AuthResult.Success<User>>(result)
    }

    @Test
    fun `authenticate does NOT enforce expiry when lastPasswordChangeAt is null`() {
        // Null timestamp = user created before expiry policy was enabled.
        // We must NOT lock them out to prevent mass lockouts when policy is first activated.
        val tenant = testTenant.copy(securityConfig = testTenant.securityConfig.copy(passwordMaxAgeDays = 30))
        tenants.clear()
        tenants.add(tenant)

        // User has never changed password (or was created before the policy was enabled)
        val legacyUser = activeUser.copy(lastPasswordChangeAt = null)
        users.clear()
        users.add(legacyUser)

        val result = svc.authenticate("acme", "alice", "correct-pass")

        assertIs<AuthResult.Success<User>>(result)
    }

    @Test
    fun `authenticate does NOT enforce expiry when policy is zero (disabled)`() {
        // passwordMaxAgeDays = 0 means "never expires"
        val tenant = testTenant.copy(securityConfig = testTenant.securityConfig.copy(passwordMaxAgeDays = 0))
        tenants.clear()
        tenants.add(tenant)

        val oldUser =
            activeUser.copy(
                lastPasswordChangeAt = Instant.now().minusSeconds(1000L * 86_400),
            )
        users.clear()
        users.add(oldUser)

        val result = svc.authenticate("acme", "alice", "correct-pass")

        assertIs<AuthResult.Success<User>>(result)
    }

    // =========================================================================
    // register()
    // =========================================================================

    @Test
    fun `register returns TenantNotFound for unknown slug`() {
        val result = svc.register("no-such", "bob", "bob@x.com", "Bob", "pass", "pass", "http://localhost")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.TenantNotFound>(result.error)
    }

    @Test
    fun `register returns RegistrationDisabled when tenant has registration off`() {
        val closed = testTenant.copy(registrationEnabled = false)
        tenants.clear()
        tenants.add(closed)

        val result = svc.register("acme", "bob", "bob@x.com", "Bob", "pass", "pass", "http://localhost")

        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.RegistrationDisabled>(result.error)
    }

    @Test
    fun `register returns ValidationError for blank required fields`() {
        val result = svc.register("acme", "", "bob@x.com", "Bob", "pass", "pass", "http://localhost")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.ValidationError>(result.error)
    }

    @Test
    fun `register returns ValidationError for malformed email`() {
        val result = svc.register("acme", "bob", "not-an-email", "Bob", "pass8!", "pass8!", "http://localhost")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.ValidationError>(result.error)
    }

    @Test
    fun `register returns ValidationError when passwords do not match`() {
        val result = svc.register("acme", "bob", "bob@x.com", "Bob", "passA1!x", "passB1!x", "http://localhost")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.ValidationError>(result.error)
    }

    @Test
    fun `register returns UserAlreadyExists for duplicate username`() {
        // alice is already seeded by @BeforeTest
        val result =
            svc.register(
                "acme",
                "alice",
                "other@x.com",
                "Other",
                "Password8!",
                "Password8!",
                "http://localhost",
            )
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.UserAlreadyExists>(result.error)
    }

    @Test
    fun `register returns EmailAlreadyExists for duplicate email`() {
        val result =
            svc.register(
                "acme",
                "newuser",
                "alice@example.com",
                "New",
                "Password8!",
                "Password8!",
                "http://localhost",
            )
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.EmailAlreadyExists>(result.error)
    }

    @Test
    fun `register creates user, records audit event, and returns the saved user on success`() {
        val result =
            svc.register(
                "acme",
                "bob",
                "bob@x.com",
                "Bob Builder",
                "securePa33!",
                "securePa33!",
                "http://localhost",
            )

        assertIs<AuthResult.Success<User>>(result)
        val saved = result.value
        assertEquals("bob", saved.username)
        assertEquals("bob@x.com", saved.email)
        assertNotNull(saved.id)
        assertTrue(auditLog.hasEvent(AuditEventType.REGISTER_SUCCESS))
    }

    // =========================================================================
    // login()
    // =========================================================================

    @Test
    fun `login returns InvalidCredentials for wrong password`() {
        val result = svc.login("acme", "alice", "wrong-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `login returns token set and persists session on success`() {
        val result = svc.login("acme", "alice", "correct-pass")

        assertIs<AuthResult.Success<*>>(result)
        // A session must have been persisted
        val activeSessions = sessions.findActiveByUser(TenantId(1), UserId(10))
        assertEquals(1, activeSessions.size)
    }

    @Test
    fun `login evicts oldest session when concurrent session limit is exceeded`() {
        val limitedTenant = testTenant.copy(maxConcurrentSessions = 2)
        tenants.clear()
        tenants.add(limitedTenant)

        // Log in 3 times — the first session should be evicted after the third
        svc.login("acme", "alice", "correct-pass")
        svc.login("acme", "alice", "correct-pass")
        svc.login("acme", "alice", "correct-pass")

        val active = sessions.findActiveByUser(TenantId(1), UserId(10))
        assertEquals(2, active.size, "Only 2 sessions should remain active after the limit enforcement")
    }

    // =========================================================================
    // authenticate() — PendingSetup guard (invite flow)
    // =========================================================================

    @Test
    fun `authenticate returns PendingSetup when user has SET_PASSWORD in requiredActions`() {
        users.clear()
        users.add(
            activeUser.copy(
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                requiredActions = setOf(RequiredAction.SET_PASSWORD),
            ),
        )
        val result = svc.authenticate("acme", "alice", "anything")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.PendingSetup>(result.error)
    }

    @Test
    fun `authenticate PendingSetup emits LOGIN_FAILED audit event`() {
        users.clear()
        users.add(
            activeUser.copy(
                passwordHash = User.SENTINEL_PASSWORD_HASH,
                requiredActions = setOf(RequiredAction.SET_PASSWORD),
            ),
        )
        svc.authenticate("acme", "alice", "anything")
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate succeeds after invite is accepted and requiredActions cleared`() {
        users.clear()
        users.add(
            activeUser.copy(
                passwordHash = hasher.hash("new-pass"),
                requiredActions = emptySet(),
            ),
        )
        val result = svc.authenticate("acme", "alice", "new-pass")
        assertIs<AuthResult.Success<User>>(result)
    }

    // =========================================================================
    // authenticate() — CHANGE_PASSWORD guard (admin-forced password rotation)
    // =========================================================================

    @Test
    fun `authenticate returns PasswordChangeRequired after valid password when CHANGE_PASSWORD is set`() {
        users.clear()
        users.add(
            activeUser.copy(
                requiredActions = setOf(RequiredAction.CHANGE_PASSWORD),
            ),
        )
        val result = svc.authenticate("acme", "alice", "correct-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.PasswordChangeRequired>(result.error)
    }

    @Test
    fun `authenticate returns InvalidCredentials (not PasswordChangeRequired) on wrong password`() {
        // CHANGE_PASSWORD check must happen AFTER password verify, otherwise
        // response differences would leak which accounts have the flag set.
        users.clear()
        users.add(
            activeUser.copy(
                requiredActions = setOf(RequiredAction.CHANGE_PASSWORD),
            ),
        )
        val result = svc.authenticate("acme", "alice", "wrong-password")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `authenticate PasswordChangeRequired emits LOGIN_FAILED audit event`() {
        users.clear()
        users.add(
            activeUser.copy(
                requiredActions = setOf(RequiredAction.CHANGE_PASSWORD),
            ),
        )
        svc.authenticate("acme", "alice", "correct-pass")
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `authenticate succeeds after CHANGE_PASSWORD is cleared`() {
        users.clear()
        users.add(
            activeUser.copy(
                passwordHash = hasher.hash("fresh-pass"),
                requiredActions = emptySet(),
            ),
        )
        val result = svc.authenticate("acme", "alice", "fresh-pass")
        assertIs<AuthResult.Success<User>>(result)
    }

    // =========================================================================
    // Password-login toggle
    // =========================================================================

    @Test
    fun `authenticate returns PasswordLoginDisabled when tenant policy disables password`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = testTenant.securityConfig.copy(passwordLoginEnabled = false),
            ),
        )

        val result = svc.authenticate("acme", "alice", "correct-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.PasswordLoginDisabled>(result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_REJECTED_POLICY))
    }

    @Test
    fun `authenticate does not record LOGIN_FAILED when password is disabled`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = testTenant.securityConfig.copy(passwordLoginEnabled = false),
            ),
        )
        svc.authenticate("acme", "alice", "wrong-pass")
        assertTrue(!auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `register returns PasswordLoginDisabled when tenant policy disables password`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = testTenant.securityConfig.copy(passwordLoginEnabled = false),
            ),
        )

        val result =
            svc.register(
                tenantSlug = "acme",
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob",
                rawPassword = "any-pass",
                confirmPassword = "any-pass",
                baseUrl = "https://example.com",
            )
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.PasswordLoginDisabled>(result.error)
    }

    // -------------------------------------------------------------------------
    // M6: timing-equalising dummy verify on enumeration-vector paths
    // -------------------------------------------------------------------------

    @Test
    fun `authenticate runs a verify call on the unknown-username path so timing matches`() {
        val before = hasher.verifyCallCount
        svc.authenticate("acme", "no-such-user", "anything")
        assertTrue(hasher.verifyCallCount > before, "Dummy verify must run when the username is unknown")
    }

    @Test
    fun `authenticate runs a verify call on the disabled-user path`() {
        users.clear()
        users.add(activeUser.copy(enabled = false))
        val before = hasher.verifyCallCount
        svc.authenticate("acme", "alice", "correct-pass")
        assertTrue(hasher.verifyCallCount > before, "Dummy verify must run when the account is disabled")
    }

    @Test
    fun `authenticate runs a verify call on the locked-out path`() {
        users.clear()
        users.add(activeUser.copy(lockedUntil = Instant.now().plusSeconds(600)))
        val before = hasher.verifyCallCount
        svc.authenticate("acme", "alice", "correct-pass")
        assertTrue(hasher.verifyCallCount > before, "Dummy verify must run when the account is locked")
    }

    @Test
    fun `authenticate runs a verify call on the pending-setup path`() {
        users.clear()
        users.add(activeUser.copy(requiredActions = setOf(RequiredAction.SET_PASSWORD)))
        val before = hasher.verifyCallCount
        svc.authenticate("acme", "alice", "correct-pass")
        assertTrue(hasher.verifyCallCount > before, "Dummy verify must run when the user has a pending invite")
    }

    // -------------------------------------------------------------------------
    // Login identifier resolution (username / email / either)
    // -------------------------------------------------------------------------

    @Test
    fun `authenticate succeeds by email in EMAIL mode`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = SecurityConfig(loginIdentifierMode = LoginIdentifierMode.EMAIL),
            ),
        )
        val result = svc.authenticate("acme", "alice@example.com", "correct-pass")
        assertIs<AuthResult.Success<User>>(result)
    }

    @Test
    fun `authenticate rejects username in EMAIL mode`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = SecurityConfig(loginIdentifierMode = LoginIdentifierMode.EMAIL),
            ),
        )
        val result = svc.authenticate("acme", "alice", "correct-pass")
        assertIs<AuthResult.Failure>(result)
        assertIs<AuthError.InvalidCredentials>(result.error)
    }

    @Test
    fun `authenticate accepts both identifiers in EITHER mode`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = SecurityConfig(loginIdentifierMode = LoginIdentifierMode.EITHER),
            ),
        )
        assertIs<AuthResult.Success<User>>(svc.authenticate("acme", "alice", "correct-pass"))
        assertIs<AuthResult.Success<User>>(svc.authenticate("acme", "alice@example.com", "correct-pass"))
    }

    @Test
    fun `ambiguous identifier fails with the same error as a miss and is audited`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = SecurityConfig(loginIdentifierMode = LoginIdentifierMode.EITHER),
            ),
        )
        users.clear()
        users.add(activeUser.copy(id = UserId(20), username = "shared@example.com", email = "one@example.com"))
        users.add(activeUser.copy(id = UserId(21), username = "two", email = "shared@example.com"))

        val ambiguous = svc.authenticate("acme", "shared@example.com", "correct-pass")
        assertIs<AuthResult.Failure>(ambiguous)
        assertIs<AuthError.InvalidCredentials>(ambiguous.error)

        val event = auditLog.events.last()
        assertEquals(AuditEventType.LOGIN_FAILED, event.eventType)
        assertEquals("ambiguous_identifier", event.details["reason"])
        assertNull(event.userId, "must not identify either colliding account")
        assertTrue(
            event.details.values.none { it.contains("shared@example.com") },
            "must not record the raw submitted identifier",
        )
    }

    @Test
    fun `ambiguous and missing identifiers both perform exactly one hash operation`() {
        tenants.clear()
        tenants.add(
            testTenant.copy(
                securityConfig = SecurityConfig(loginIdentifierMode = LoginIdentifierMode.EITHER),
            ),
        )
        users.clear()
        users.add(activeUser.copy(id = UserId(20), username = "shared@example.com", email = "one@example.com"))
        users.add(activeUser.copy(id = UserId(21), username = "two", email = "shared@example.com"))

        hasher.verifyCount = 0
        svc.authenticate("acme", "shared@example.com", "correct-pass")
        val ambiguousHashes = hasher.verifyCount

        hasher.verifyCount = 0
        svc.authenticate("acme", "nobody-at-all", "correct-pass")
        val missHashes = hasher.verifyCount

        assertEquals(1, ambiguousHashes)
        assertEquals(missHashes, ambiguousHashes)
    }

    @Test
    fun `authenticate trims surrounding whitespace from the submitted identifier`() {
        // USERNAME mode is the tenant default (testTenant / @BeforeTest).
        val trimmed = svc.authenticate("acme", "  alice  ", "correct-pass")
        val exact = svc.authenticate("acme", "alice", "correct-pass")

        assertIs<AuthResult.Success<User>>(trimmed)
        assertIs<AuthResult.Success<User>>(exact)
        assertEquals(exact.value.id, trimmed.value.id)
    }
}
