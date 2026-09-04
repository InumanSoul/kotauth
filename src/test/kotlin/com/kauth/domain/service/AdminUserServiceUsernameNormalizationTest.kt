package com.kauth.domain.service

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Part A of the login-identifier hardening wave: [AdminUserService.createUser] and the rename
 * path shared by `updateUser`/`replaceUserProfile` (`resolveUsername`) must normalize a username
 * (trim + lowercase) FIRST, then validate against [UsernamePolicy.USERNAME_PATTERN] — accepting
 * a merely differently-cased or padded value, rejecting one that is still invalid afterward.
 */
class AdminUserServiceUsernameNormalizationTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val sessions = FakeSessionRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val emailPort = FakeEmailPort()

    private val credentialFlowService =
        CredentialFlowService(
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            evTokenRepo = FakeEmailVerificationTokenRepository(),
            prTokenRepo = FakePasswordResetTokenRepository(),
            emailPort = emailPort,
        )

    private val svc =
        AdminUserService(
            tenantRepository = tenants,
            userRepository = users,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            credentialFlowService = credentialFlowService,
            collisionCheck = IdentifierCollisionCheck(users),
            usernameGenerator = UsernameGenerator(users),
        )

    private val tenantId = TenantId(1)

    @BeforeTest
    fun setup() {
        tenants.clear()
        users.clear()
        tenants.add(Tenant(id = tenantId, slug = "acme", displayName = "Acme", issuerUrl = null))
    }

    // -------------------------------------------------------------------------
    // createUser
    // -------------------------------------------------------------------------

    @Test
    fun `createUser normalizes a mixed-case username to lowercase`() {
        val result = svc.createUser(tenantId, "Dave", "dave@x.com", "Dave", password = "Password8!")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("dave", result.value.username)
    }

    @Test
    fun `createUser trims surrounding whitespace from the username`() {
        val result = svc.createUser(tenantId, "  ana  ", "ana@x.com", "Ana", password = "Password8!")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("ana", result.value.username)
    }

    @Test
    fun `createUser rejects a username with characters outside the allowed set even after normalizing`() {
        val result = svc.createUser(tenantId, "john doe", "johndoe@x.com", "John Doe", password = "Password8!")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals(null, users.findByEmail(tenantId, "johndoe@x.com"))
    }

    // -------------------------------------------------------------------------
    // resolveUsername (via updateUser)
    // -------------------------------------------------------------------------

    private fun seedUser(username: String = "carol"): User =
        users.add(
            User(
                tenantId = tenantId,
                username = username,
                email = "carol@x.com",
                fullName = "Carol",
                passwordHash = hasher.hash("x"),
                enabled = true,
            ),
        )

    @Test
    fun `updateUser rename normalizes a mixed-case username to lowercase`() {
        val user = seedUser()
        val result = svc.updateUser(user.id!!, tenantId, username = "Dave")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("dave", result.value.username)
    }

    @Test
    fun `updateUser rename trims surrounding whitespace from the username`() {
        val user = seedUser()
        val result = svc.updateUser(user.id!!, tenantId, username = "  dave  ")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("dave", result.value.username)
    }

    @Test
    fun `updateUser rename rejects a username with characters outside the allowed set`() {
        val user = seedUser()
        val result = svc.updateUser(user.id!!, tenantId, username = "john doe")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        // The stored username must be unchanged.
        assertEquals("carol", users.findById(user.id, tenantId)!!.username)
    }

    @Test
    fun `updateUser resubmitting the same username in a different case is not treated as a rename`() {
        val user = seedUser("dave")
        // Normalizes to the already-stored value — must succeed as a no-op rename, not a collision.
        val result = svc.updateUser(user.id!!, tenantId, username = "Dave")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("dave", result.value.username)
    }
}
