package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WorkspaceSettingsUpdate
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeCorsPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeEmailVerificationTokenRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakePasswordPolicyPort
import com.kauth.fakes.FakePasswordResetTokenRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdminServicesTest {
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
    private val emailBranding = FakeTenantEmailBrandingRepository()

    private val credentialFlowService =
        CredentialFlowService(
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

    private val accountSvc =
        AdminAccountService(
            tenantRepository = tenants,
            userRepository = users,
            auditLog = auditLog,
            credentialFlowService = credentialFlowService,
        )

    private val appSvc =
        ApplicationManagementService(
            applicationRepository = apps,
            tenantRepository = tenants,
            passwordHasher = hasher,
            auditLog = auditLog,
        )

    private val wsSvc =
        WorkspaceSettingsService(
            tenantRepository = tenants,
            auditLog = auditLog,
            emailBrandingRepository = emailBranding,
        )

    private val collisionCheck = IdentifierCollisionCheck(users)
    private val usernameGenerator = UsernameGenerator(users)

    private val userSvc =
        AdminUserService(
            tenantRepository = tenants,
            userRepository = users,
            sessionRepository = sessions,
            passwordHasher = hasher,
            auditLog = auditLog,
            credentialFlowService = credentialFlowService,
            collisionCheck = collisionCheck,
            usernameGenerator = usernameGenerator,
            passwordPolicy = passwordPolicy,
            emailPort = emailPort,
        )

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            securityConfig = SecurityConfig(passwordMinLength = 8),
            smtpHost = "smtp.example.com",
            smtpFromAddress = "no-reply@acme.com",
            smtpEnabled = true,
        )

    private val alice
        get() =
            User(
                id = UserId(10),
                tenantId = TenantId(1),
                username = "alice",
                email = "alice@example.com",
                fullName = "Alice Test",
                passwordHash = hasher.hash("pass"),
                enabled = true,
            )

    private val testApp =
        Application(
            id = ApplicationId(100),
            tenantId = TenantId(1),
            clientId = "my-app",
            name = "My App",
            description = "Test app",
            accessType = AccessType.CONFIDENTIAL,
            enabled = true,
            redirectUris = listOf("http://localhost/callback"),
            grantTypes = GrantType.defaultsFor(AccessType.CONFIDENTIAL),
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
        tenants.add(tenant)
        users.add(alice)
        apps.add(testApp, secretHash = hasher.hash("old-secret"))
    }

    // =========================================================================
    // updateWorkspaceSettings
    // =========================================================================

    @Test
    fun `updateWorkspaceSettings - tenant not found`() {
        val result = callUpdateSettings(slug = "unknown")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - blank display name`() {
        val result = callUpdateSettings(displayName = "  ")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - token expiry too low`() {
        val result = callUpdateSettings(tokenExpirySeconds = 30)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - refresh expiry less than access expiry`() {
        val result = callUpdateSettings(tokenExpirySeconds = 3600, refreshTokenExpirySeconds = 1800)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - password minLength out of range`() {
        val result = callUpdateSettings(passwordPolicyMinLength = 3)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - invalid mfa policy`() {
        val result = callUpdateSettings(mfaPolicy = "invalid")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings - success updates tenant`() {
        val result = callUpdateSettings(displayName = "New Name", tokenExpirySeconds = 7200)
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals("New Name", result.value.displayName)
        assertEquals(7200L, result.value.tokenExpirySeconds)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_TENANT_UPDATED))
    }

    // =========================================================================
    // createUser
    // =========================================================================

    @Test
    fun `createUser - tenant not found`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(999),
                username = "bob",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `createUser generates a username when none is supplied`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "  ",
                email = "ana@company-a.com",
                fullName = "Ana Ruiz",
                password = "password123",
                givenName = "Ana",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertTrue(result.value.username.startsWith("ana"), "got ${result.value.username}")
    }

    @Test
    fun `createUser - invalid username characters`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bad user!",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - email-shaped username is accepted`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "ada.lovelace@example.com",
                email = "ada@x.com",
                fullName = "Ada",
                password = "password123",
            )
        assertIs<AdminResult.Success<User>>(result)
    }

    @Test
    fun `createUser - plus-addressed username is accepted`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "ada+scim@example.com",
                email = "ada2@x.com",
                fullName = "Ada",
                password = "password123",
            )
        assertIs<AdminResult.Success<User>>(result)
    }

    @Test
    fun `createUser - username with slash is rejected`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob/smith",
                email = "bob@x.com",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - invalid email`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "not-email",
                fullName = "Bob",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - duplicate username`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "alice",
                email = "new@x.com",
                fullName = "Alice 2",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createUser - duplicate email`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "newuser",
                email = "alice@example.com",
                fullName = "New",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `createUser - password policy violation`() {
        passwordPolicy.validationError = "Too weak"
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@x.com",
                fullName = "Bob",
                password = "weak",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser - success`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                password = "secure-pass-123",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("bob", result.value.username)
        assertEquals(true, result.value.emailVerified, "Admin-created users should be email-verified")
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_CREATED))
    }

    @Test
    fun `createUser rejects a username equal to another users email`() {
        users.add(alice.copy(id = UserId(50), username = "bob", email = "carol@example.com"))
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "carol@example.com",
                email = "newperson@example.com",
                fullName = "New Person",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser rejects an email equal to another users username`() {
        users.add(alice.copy(id = UserId(51), username = "dave@example.com", email = "dave-alt@example.com"))
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "eve",
                email = "dave@example.com",
                fullName = "Eve",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser allows a username equal to the same users own email`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "frank@example.com",
                email = "frank@example.com",
                fullName = "Frank",
                password = "password123",
            )
        assertIs<AdminResult.Success<User>>(result)
    }

    @Test
    fun `createUser rejects an email equal to another users username in different case`() {
        users.add(alice.copy(id = UserId(52), username = "Dave@Example.com", email = "dave-alt2@example.com"))
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "gail",
                email = "dave@example.com",
                fullName = "Gail",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser rejects a username equal to another users email in different case`() {
        users.add(alice.copy(id = UserId(53), username = "harold", email = "Carol2@Example.com"))
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "carol2@example.com",
                email = "newperson2@example.com",
                fullName = "New Person 2",
                password = "password123",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser allows a username equal to the same users own email in different case`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "Frank2@Example.com",
                email = "frank2@example.com",
                fullName = "Frank Two",
                password = "password123",
            )
        assertIs<AdminResult.Success<User>>(result)
    }

    // =========================================================================
    // createUser — invite mode
    // =========================================================================

    @Test
    fun `createUser invite - stores sentinel hash and SET_PASSWORD action`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals(User.SENTINEL_PASSWORD_HASH, result.value.passwordHash)
        assertTrue(RequiredAction.SET_PASSWORD in result.value.requiredActions)
        assertEquals(false, result.value.emailVerified, "Invite users should not be pre-verified")
    }

    @Test
    fun `createUser invite - sends invite email when SMTP ready`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("invite", emailPort.sent[0].type)
        assertTrue(auditLog.hasEvent(AuditEventType.USER_INVITE_SENT))
    }

    @Test
    fun `createUser invite - creates token with purpose INVITE`() {
        userSvc.createUser(
            tenantId = TenantId(1),
            username = "bob",
            email = "bob@example.com",
            fullName = "Bob Test",
            sendInvite = true,
            baseUrl = "http://localhost:8080",
        )
        val tokens = prTokenRepo.all()
        assertEquals(1, tokens.size)
        assertEquals(TokenPurpose.INVITE, tokens[0].purpose)
    }

    @Test
    fun `createUser invite - does not send email when SMTP not configured`() {
        val noSmtpTenant = tenant.copy(id = TenantId(2), slug = "no-smtp", smtpHost = null, smtpEnabled = false)
        tenants.add(noSmtpTenant)
        val result =
            userSvc.createUser(
                tenantId = TenantId(2),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals(0, emailPort.sent.size, "No email should be sent when SMTP is not configured")
        assertTrue(!auditLog.hasEvent(AuditEventType.USER_INVITE_SENT))
    }

    @Test
    fun `createUser invite - dispatchInvite=false suppresses the inline send`() {
        // Used by SCIM provisioning, which must not hold a DB transaction open across the
        // SMTP round-trip — see dispatchPendingInvite below.
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
                dispatchInvite = false,
            )
        assertIs<AdminResult.Success<User>>(result)
        assertTrue(RequiredAction.SET_PASSWORD in result.value.requiredActions, "still an invite-style user")
        assertEquals(0, emailPort.sent.size)
        assertTrue(!auditLog.hasEvent(AuditEventType.USER_INVITE_SENT))
    }

    @Test
    fun `dispatchPendingInvite - sends the invite a suppressed createUser deferred`() {
        val created =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
                dispatchInvite = false,
            )
        assertIs<AdminResult.Success<User>>(created)

        val result = userSvc.dispatchPendingInvite(created.value.id!!, TenantId(1), "http://localhost:8080")

        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("invite", emailPort.sent[0].type)
        assertTrue(auditLog.hasEvent(AuditEventType.USER_INVITE_SENT))
    }

    @Test
    fun `dispatchPendingInvite - no-op when the user has no pending invite`() {
        val result = userSvc.dispatchPendingInvite(alice.id!!, TenantId(1), "http://localhost:8080")

        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(0, emailPort.sent.size)
    }

    @Test
    fun `dispatchPendingInvite - no-op when SMTP is not configured`() {
        val noSmtpTenant = tenant.copy(id = TenantId(2), slug = "no-smtp", smtpHost = null, smtpEnabled = false)
        tenants.add(noSmtpTenant)
        val created =
            userSvc.createUser(
                tenantId = TenantId(2),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                sendInvite = true,
                baseUrl = "http://localhost:8080",
                dispatchInvite = false,
            )
        assertIs<AdminResult.Success<User>>(created)

        val result = userSvc.dispatchPendingInvite(created.value.id!!, TenantId(2), "http://localhost:8080")

        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(0, emailPort.sent.size)
    }

    @Test
    fun `dispatchPendingInvite - not found for a user in another tenant`() {
        val result = userSvc.dispatchPendingInvite(alice.id!!, TenantId(2), "http://localhost:8080")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `createUser password mode - requires password`() {
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob Test",
                password = null,
                sendInvite = false,
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    // =========================================================================
    // resendInvite
    // =========================================================================

    @Test
    fun `resendInvite - user not found`() {
        val result = userSvc.resendInvite(UserId(999), TenantId(1), "http://localhost:8080")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `resendInvite - user has no pending invite`() {
        val result = userSvc.resendInvite(alice.id!!, TenantId(1), "http://localhost:8080")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `resendInvite - success sends email and records audit event`() {
        val invitedUser =
            users.add(
                alice.copy(
                    id = null,
                    username = "invited",
                    email = "invited@example.com",
                    passwordHash = User.SENTINEL_PASSWORD_HASH,
                    requiredActions = setOf(RequiredAction.SET_PASSWORD),
                ),
            )
        val result = userSvc.resendInvite(invitedUser.id!!, TenantId(1), "http://localhost:8080")
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("invite", emailPort.sent[0].type)
        assertTrue(auditLog.hasEvent(AuditEventType.USER_INVITE_SENT))
    }

    // =========================================================================
    // updateUser
    // =========================================================================

    @Test
    fun `updateUser - user not found`() {
        val result = userSvc.updateUser(userId = UserId(999), tenantId = TenantId(1), email = "x@x.com", fullName = "X")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateUser - tenant mismatch`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(99), email = "x@x.com", fullName = "X")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateUser - success`() {
        val result =
            userSvc.updateUser(
                userId = UserId(10),
                tenantId = TenantId(1),
                email = "newalice@example.com",
                fullName = "Alice Updated",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("newalice@example.com", result.value.email)
        assertEquals("Alice Updated", result.value.fullName)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_UPDATED))
    }

    @Test
    fun `updateUser - partial update email only`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), email = "new@example.com")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("new@example.com", result.value.email)
        assertEquals("Alice Test", result.value.fullName)
    }

    @Test
    fun `updateUser - partial update fullName only`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), fullName = "Alice Renamed")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice@example.com", result.value.email)
        assertEquals("Alice Renamed", result.value.fullName)
    }

    @Test
    fun `updateUser - no fields keeps existing values`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1))
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice@example.com", result.value.email)
        assertEquals("Alice Test", result.value.fullName)
    }

    @Test
    fun `updateUser - changes the username`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "alice.new")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice.new", result.value.username)
        assertEquals("alice.new", users.findById(UserId(10), TenantId(1))!!.username)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_UPDATED))
    }

    @Test
    fun `updateUser - rejects a username that is another user's email`() {
        users.add(alice.copy(id = UserId(20), username = "zoe", email = "taken@example.com"))
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "taken@example.com")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateUser - rejects a username already taken as another user's username`() {
        users.add(alice.copy(id = UserId(20), username = "zoe", email = "zoe@example.com"))
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "zoe")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Conflict>(result.error)
    }

    @Test
    fun `updateUser - allows setting the username to the user's own email`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "alice@example.com")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice@example.com", result.value.username)
    }

    @Test
    fun `updateUser - rejects an invalid username format`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "not valid!")
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateUser - null username leaves the existing username untouched`() {
        val result = userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), fullName = "Alice Renamed")
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice", result.value.username)
    }

    @Test
    fun `updateUser - audit records the new username only when it actually changed`() {
        auditLog.clear()
        userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), username = "alice.renamed")
        val event = auditLog.events.last { it.eventType == AuditEventType.ADMIN_USER_UPDATED }
        assertEquals("alice.renamed", event.details["newUsername"])
    }

    @Test
    fun `updateUser - audit omits newUsername when the username is unchanged`() {
        auditLog.clear()
        userSvc.updateUser(userId = UserId(10), tenantId = TenantId(1), fullName = "Alice Renamed")
        val event = auditLog.events.last { it.eventType == AuditEventType.ADMIN_USER_UPDATED }
        assertTrue(!event.details.containsKey("newUsername"))
    }

    @Test
    fun `replaceUserProfile - username null leaves the existing username untouched`() {
        val result =
            userSvc.replaceUserProfile(
                userId = UserId(10),
                tenantId = TenantId(1),
                email = "alice@example.com",
                fullName = "Alice Test",
                externalId = null,
                givenName = null,
                familyName = null,
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice", result.value.username)
    }

    @Test
    fun `replaceUserProfile - renames the username when provided`() {
        val result =
            userSvc.replaceUserProfile(
                userId = UserId(10),
                tenantId = TenantId(1),
                email = "alice@example.com",
                fullName = "Alice Test",
                externalId = null,
                givenName = null,
                familyName = null,
                username = "alice.renamed",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice.renamed", result.value.username)
    }

    @Test
    fun `updateUser - unrelated fields updatable for a stored username that predates format validation`() {
        users.add(alice.copy(username = "john doe"))
        val result =
            userSvc.updateUser(
                userId = UserId(10),
                tenantId = TenantId(1),
                email = "newalice@example.com",
                fullName = "Alice Updated",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("john doe", result.value.username)
        assertEquals("newalice@example.com", result.value.email)
        assertEquals("Alice Updated", result.value.fullName)
    }

    @Test
    fun `updateUser - renaming a stored non-conforming username to an invalid format still fails`() {
        users.add(alice.copy(username = "john doe"))
        val result =
            userSvc.updateUser(
                userId = UserId(10),
                tenantId = TenantId(1),
                username = "not valid!",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertEquals("john doe", users.findById(UserId(10), TenantId(1))!!.username)
    }

    @Test
    fun `replaceUserProfile - unrelated fields updatable for a stored username that predates format validation`() {
        users.add(alice.copy(username = "john doe"))
        val result =
            userSvc.replaceUserProfile(
                userId = UserId(10),
                tenantId = TenantId(1),
                email = "newalice@example.com",
                fullName = "Alice Updated",
                externalId = null,
                givenName = null,
                familyName = null,
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("john doe", result.value.username)
        assertEquals("newalice@example.com", result.value.email)
        assertEquals("Alice Updated", result.value.fullName)
    }

    @Test
    fun `updateUser - a pre-existing colliding pair can still have unrelated fields updated`() {
        // Legal before this feature existed: user 10's username equals user 20's email.
        users.add(alice.copy(username = "taken@example.com"))
        users.add(alice.copy(id = UserId(20), username = "zoe", email = "taken@example.com"))
        val result =
            userSvc.updateUser(
                userId = UserId(10),
                tenantId = TenantId(1),
                fullName = "Alice Updated",
            )
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("Alice Updated", result.value.fullName)
        assertEquals("taken@example.com", result.value.username)
    }

    // =========================================================================
    // setUserEnabled
    // =========================================================================

    @Test
    fun `setUserEnabled - user not found`() {
        val result = userSvc.setUserEnabled(userId = UserId(999), tenantId = TenantId(1), enabled = false)
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `setUserEnabled - disables user`() {
        val result = userSvc.setUserEnabled(userId = UserId(10), tenantId = TenantId(1), enabled = false)
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(false, users.findById(UserId(10), TenantId(1))!!.enabled)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_DISABLED))
    }

    // =========================================================================
    // updateApplication
    // =========================================================================

    @Test
    fun `updateApplication - app not found`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(999),
                tenantId = TenantId(1),
                name = "X",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateApplication - tenant mismatch`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(99),
                name = "X",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateApplication - blank name`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                name = "  ",
                description = null,
                accessType = "public",
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - success`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                name = "Renamed App",
                description = "Updated",
                accessType = "public",
                redirectUris = listOf("http://new/callback"),
                grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("Renamed App", result.value.name)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_UPDATED))
    }

    @Test
    fun `updateApplication - partial update name only`() {
        val result = appSvc.updateApplication(appId = ApplicationId(100), tenantId = TenantId(1), name = "New Name")
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("New Name", result.value.name)
        assertEquals("Test app", result.value.description)
        assertEquals(AccessType.CONFIDENTIAL, result.value.accessType)
        assertEquals(listOf("http://localhost/callback"), result.value.redirectUris)
    }

    @Test
    fun `updateApplication - partial update description only`() {
        val result =
            appSvc.updateApplication(appId = ApplicationId(100), tenantId = TenantId(1), description = "New desc")
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("My App", result.value.name)
        assertEquals("New desc", result.value.description)
    }

    @Test
    fun `updateApplication - no fields keeps existing values`() {
        val result = appSvc.updateApplication(appId = ApplicationId(100), tenantId = TenantId(1))
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("My App", result.value.name)
        assertEquals("Test app", result.value.description)
        assertEquals(AccessType.CONFIDENTIAL, result.value.accessType)
        assertEquals(listOf("http://localhost/callback"), result.value.redirectUris)
    }

    @Test
    fun `updateApplication - launcherUrl matching redirect origin succeeds`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                launcherUrl = "http://localhost/home",
                launcherVisible = true,
                launcherDisplayOrder = 5,
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("http://localhost/home", result.value.launcherUrl)
        assertEquals(true, result.value.launcherVisible)
        assertEquals(5, result.value.launcherDisplayOrder)
    }

    @Test
    fun `updateApplication - launcherUrl mismatched origin rejected`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                launcherUrl = "https://attacker.example.com/home",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - launcherUrl with non-http scheme rejected`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                launcherUrl = "javascript:alert(1)",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - rejects bearer only access type when grants are still selected`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                accessType = "bearer_only",
                grantTypes = setOf(GrantType.CLIENT_CREDENTIALS),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - rejects when redirect URIs is empty`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                redirectUris = emptyList(),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
        assertTrue(result.error.message.contains("redirect URI", ignoreCase = true))
    }

    @Test
    fun `updateApplication - launcherUrl rejected when no redirect URIs registered`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                redirectUris = emptyList(),
                launcherUrl = "http://localhost/home",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - blank launcherUrl clears value`() {
        apps.clear()
        apps.add(testApp.copy(launcherUrl = "http://localhost/home"))

        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                launcherUrl = "   ",
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals(null, result.value.launcherUrl)
    }

    @Test
    fun `updateApplication - iconUrl with non-http scheme rejected`() {
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                iconUrl = "javascript:alert(1)",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateApplication - iconUrl on a different origin is allowed`() {
        // Icons may be served from a CDN, unlike launcher URLs.
        val result =
            appSvc.updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                iconUrl = "https://cdn.example.com/icon.svg",
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals("https://cdn.example.com/icon.svg", result.value.iconUrl)
    }

    // =========================================================================
    // setApplicationEnabled
    // =========================================================================

    @Test
    fun `setApplicationEnabled - disables app`() {
        val result = appSvc.setApplicationEnabled(appId = ApplicationId(100), tenantId = TenantId(1), enabled = false)
        assertIs<AdminResult.Success<Unit>>(result)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_DISABLED))
    }

    // =========================================================================
    // CORS cache invalidation hooks
    // =========================================================================

    private fun appSvcWithCors(corsPort: FakeCorsPort) =
        ApplicationManagementService(
            applicationRepository = apps,
            tenantRepository = tenants,
            passwordHasher = hasher,
            auditLog = auditLog,
            corsPort = corsPort,
        )

    private fun wsSvcWithCors(corsPort: FakeCorsPort) =
        WorkspaceSettingsService(
            tenantRepository = tenants,
            auditLog = auditLog,
            emailBrandingRepository = emailBranding,
            corsPort = corsPort,
        )

    @Test
    fun `updateWorkspaceSettings invalidates CORS cache for tenant slug`() {
        val corsPort = FakeCorsPort()
        val result = wsSvcWithCors(corsPort).updateWorkspaceSettings("acme", defaultSettingsUpdate())
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals(listOf("acme"), corsPort.invalidated)
    }

    @Test
    fun `updateApplication invalidates CORS cache for tenant slug`() {
        val corsPort = FakeCorsPort()
        val result =
            appSvcWithCors(corsPort).updateApplication(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                name = "Renamed",
            )
        assertIs<AdminResult.Success<Application>>(result)
        assertEquals(listOf("acme"), corsPort.invalidated)
    }

    @Test
    fun `setApplicationEnabled invalidates CORS cache for tenant slug`() {
        val corsPort = FakeCorsPort()
        val result =
            appSvcWithCors(corsPort).setApplicationEnabled(
                appId = ApplicationId(100),
                tenantId = TenantId(1),
                enabled = false,
            )
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(listOf("acme"), corsPort.invalidated)
    }

    // =========================================================================
    // regenerateClientSecret
    // =========================================================================

    @Test
    fun `regenerateClientSecret - app not found`() {
        val result = appSvc.regenerateClientSecret(appId = ApplicationId(999), tenantId = TenantId(1))
        assertIs<AdminResult.Failure>(result)
    }

    @Test
    fun `regenerateClientSecret - success returns raw secret`() {
        val result = appSvc.regenerateClientSecret(appId = ApplicationId(100), tenantId = TenantId(1))
        assertIs<AdminResult.Success<String>>(result)
        assertTrue(result.value.isNotBlank())
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED))
    }

    // =========================================================================
    // updateSmtpConfig
    // =========================================================================

    @Test
    fun `updateSmtpConfig - tenant not found`() {
        val result = accountSvc.updateSmtpConfig("unknown", "host", 587, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but no host`() {
        val result = accountSvc.updateSmtpConfig("acme", "  ", 587, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but invalid from address`() {
        val result =
            accountSvc.updateSmtpConfig(
                "acme",
                "smtp.host.com",
                587,
                null,
                null,
                "bad-email",
                null,
                true,
                true,
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - enabled but invalid port`() {
        val result = accountSvc.updateSmtpConfig("acme", "smtp.host.com", 0, null, null, "a@b.com", null, true, true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateSmtpConfig - disabled skips validation`() {
        val result = accountSvc.updateSmtpConfig("acme", null, 587, null, null, null, null, false, false)
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals(false, result.value.smtpEnabled)
    }

    @Test
    fun `updateSmtpConfig - success`() {
        val result =
            accountSvc.updateSmtpConfig(
                "acme",
                "smtp.new.com",
                465,
                "user",
                "pass",
                "no-reply@new.com",
                "New Co",
                true,
                true,
            )
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals("smtp.new.com", result.value.smtpHost)
        assertEquals(465, result.value.smtpPort)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_SMTP_UPDATED))
    }

    // =========================================================================
    // sendPasswordResetEmail
    // =========================================================================

    @Test
    fun `sendPasswordResetEmail - user not found`() {
        val result =
            accountSvc.sendPasswordResetEmail(
                userId = UserId(999),
                tenantId = TenantId(1),
                baseUrl = "http://localhost",
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `sendPasswordResetEmail - success delegates to self-service`() {
        val result =
            accountSvc.sendPasswordResetEmail(
                userId = UserId(10),
                tenantId = TenantId(1),
                baseUrl = "http://localhost",
            )
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("password_reset", emailPort.sent[0].type)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_PASSWORD_RESET))
    }

    // =========================================================================
    // resendVerificationEmail
    // =========================================================================

    @Test
    fun `resendVerificationEmail - success`() {
        val result =
            accountSvc.resendVerificationEmail(
                userId = UserId(10),
                tenantId = TenantId(1),
                baseUrl = "http://localhost",
            )
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(1, emailPort.sent.size)
        assertEquals("verification", emailPort.sent[0].type)
    }

    // =========================================================================
    // unlockUser
    // =========================================================================

    @Test
    fun `unlockUser - user not found returns NotFound`() {
        val result = accountSvc.unlockUser(userId = UserId(999), tenantId = TenantId(1))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `unlockUser - resets failed logins and emits ACCOUNT_UNLOCKED audit event`() {
        users.recordFailedLogin(UserId(10), 5, lockedUntil = null)
        val result = accountSvc.unlockUser(userId = UserId(10), tenantId = TenantId(1))
        assertIs<AdminResult.Success<Unit>>(result)
        val fresh = users.findById(UserId(10), TenantId(1))!!
        assertEquals(0, fresh.failedLoginAttempts)
        assertTrue(auditLog.hasEvent(AuditEventType.ACCOUNT_UNLOCKED))
    }

    // =========================================================================
    // createWorkspace
    // =========================================================================

    @Test
    fun `createWorkspace - returns Validation failure when slug is blank`() {
        val result = wsSvc.createWorkspace(slug = "  ", displayName = "New Corp", issuerUrl = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createWorkspace - returns Validation failure when slug has uppercase or special chars`() {
        val result = wsSvc.createWorkspace(slug = "New_Corp!", displayName = "New Corp", issuerUrl = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createWorkspace - returns Validation failure when slug is master`() {
        val result = wsSvc.createWorkspace(slug = "master", displayName = "Master", issuerUrl = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createWorkspace - returns Validation failure when displayName is blank`() {
        val result = wsSvc.createWorkspace(slug = "new-corp", displayName = "  ", issuerUrl = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createWorkspace - returns Validation failure when slug already exists`() {
        // "acme" was seeded in @BeforeTest
        val result = wsSvc.createWorkspace(slug = "acme", displayName = "Acme Duplicate", issuerUrl = null)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createWorkspace - returns Success with created tenant on valid input`() {
        val result = wsSvc.createWorkspace(slug = "beta-corp", displayName = "Beta Corp", issuerUrl = null)
        assertIs<AdminResult.Success<Tenant>>(result)
        assertEquals("beta-corp", result.value.slug)
        assertEquals("Beta Corp", result.value.displayName)
    }

    @Test
    fun `createWorkspace - emits ADMIN_TENANT_CREATED audit event`() {
        wsSvc.createWorkspace(slug = "gamma-corp", displayName = "Gamma Corp", issuerUrl = null)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_TENANT_CREATED))
    }

    // =========================================================================
    // getUser
    // =========================================================================

    @Test
    fun `getUser - returns Success when user exists in tenant`() {
        val result = userSvc.getUser(userId = UserId(10), tenantId = TenantId(1))
        assertIs<AdminResult.Success<User>>(result)
        assertEquals("alice", result.value.username)
    }

    @Test
    fun `getUser - returns NotFound when user does not exist`() {
        val result = userSvc.getUser(userId = UserId(999), tenantId = TenantId(1))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `getUser - returns NotFound when user exists in different tenant`() {
        // Alice belongs to TenantId(1); querying from TenantId(2) must not expose her data.
        val result = userSvc.getUser(userId = UserId(10), tenantId = TenantId(2))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    // =========================================================================
    // toggleUserEnabled
    // =========================================================================

    @Test
    fun `toggleUserEnabled - disables an enabled user`() {
        // Alice starts enabled = true
        val result = userSvc.toggleUserEnabled(userId = UserId(10), tenantId = TenantId(1))
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(false, users.findById(UserId(10), TenantId(1))!!.enabled)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_DISABLED))
    }

    @Test
    fun `toggleUserEnabled - enables a disabled user`() {
        // Disable alice first so the toggle has something to flip
        users.update(alice.copy(enabled = false))
        val result = userSvc.toggleUserEnabled(userId = UserId(10), tenantId = TenantId(1))
        assertIs<AdminResult.Success<Unit>>(result)
        assertEquals(true, users.findById(UserId(10), TenantId(1))!!.enabled)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_USER_ENABLED))
    }

    @Test
    fun `toggleUserEnabled - returns NotFound for non-existent user`() {
        val result = userSvc.toggleUserEnabled(userId = UserId(999), tenantId = TenantId(1))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    // =========================================================================
    // listUsers
    // =========================================================================

    @Test
    fun `listUsers - returns users for the requested tenant only`() {
        // Add a user in a different tenant — must not appear in tenant 1's list
        users.add(
            User(
                id = UserId(20),
                tenantId = TenantId(2),
                username = "bob",
                email = "bob@other.com",
                fullName = "Bob Other",
                passwordHash = hasher.hash("pass"),
                enabled = true,
            ),
        )
        val result = userSvc.listUsers(tenantId = TenantId(1))
        assertEquals(1, result.size)
        assertEquals("alice", result.first().username)
    }

    @Test
    fun `listUsers - filters by search term`() {
        users.add(
            User(
                id = UserId(21),
                tenantId = TenantId(1),
                username = "charlie",
                email = "charlie@example.com",
                fullName = "Charlie Brown",
                passwordHash = hasher.hash("pass"),
                enabled = true,
            ),
        )
        val result = userSvc.listUsers(tenantId = TenantId(1), search = "charlie")
        assertEquals(1, result.size)
        assertEquals("charlie", result.first().username)
    }

    @Test
    fun `listUsers - returns empty list for tenant with no users`() {
        val result = userSvc.listUsers(tenantId = TenantId(99))
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun callUpdateSettings(
        slug: String = "acme",
        displayName: String = "Acme Corp",
        issuerUrl: String? = null,
        tokenExpirySeconds: Long = 3600L,
        refreshTokenExpirySeconds: Long = 86400L,
        registrationEnabled: Boolean = true,
        emailVerificationRequired: Boolean = false,
        passwordPolicyMinLength: Int = 8,
        passwordPolicyRequireSpecial: Boolean = false,
        mfaPolicy: String = "optional",
        magicLinkEnabled: Boolean = false,
        magicLinkTokenTtlMinutes: Int = 15,
        passwordLoginEnabled: Boolean = true,
    ) = wsSvc.updateWorkspaceSettings(
        slug,
        defaultSettingsUpdate().copy(
            displayName = displayName,
            issuerUrl = issuerUrl,
            tokenExpirySeconds = tokenExpirySeconds,
            refreshTokenExpirySeconds = refreshTokenExpirySeconds,
            registrationEnabled = registrationEnabled,
            emailVerificationRequired = emailVerificationRequired,
            passwordPolicyMinLength = passwordPolicyMinLength,
            passwordPolicyRequireSpecial = passwordPolicyRequireSpecial,
            mfaPolicy = mfaPolicy,
            magicLinkEnabled = magicLinkEnabled,
            magicLinkTokenTtlMinutes = magicLinkTokenTtlMinutes,
            passwordLoginEnabled = passwordLoginEnabled,
        ),
    )

    private fun defaultSettingsUpdate() =
        WorkspaceSettingsUpdate(
            displayName = "Acme Corp",
            issuerUrl = null,
            tokenExpirySeconds = 3600L,
            refreshTokenExpirySeconds = 86400L,
            registrationEnabled = true,
            emailVerificationRequired = false,
            passwordPolicyMinLength = 8,
            passwordPolicyRequireSpecial = false,
            passwordPolicyRequireUppercase = false,
            passwordPolicyRequireNumber = false,
            passwordPolicyHistoryCount = 0,
            passwordPolicyMaxAgeDays = 0,
            passwordPolicyBlacklistEnabled = false,
            mfaPolicy = "optional",
            lockoutMaxAttempts = 0,
            lockoutDurationMinutes = 15,
            corsAllowCredentials = false,
            hibpCheckEnabled = false,
            magicLinkEnabled = false,
            magicLinkTokenTtlMinutes = 15,
            passwordLoginEnabled = true,
            emailOtpSignupEnabled = false,
            emailOtpLockoutThreshold = 5,
            emailOtpLoginEnabled = false,
            passkeysEnabled = true,
            loginIdentifierMode = LoginIdentifierMode.USERNAME,
        )

    @Test
    fun `updateWorkspaceSettings coerces magic-link TTL into the 1 to 1440 range`() {
        val tooHigh = callUpdateSettings(magicLinkTokenTtlMinutes = 99_999)
        assertIs<AdminResult.Success<Tenant>>(tooHigh)
        assertEquals(1440, tooHigh.value.securityConfig.magicLinkTokenTtlMinutes)

        val tooLow = callUpdateSettings(magicLinkTokenTtlMinutes = 0)
        assertIs<AdminResult.Success<Tenant>>(tooLow)
        assertEquals(1, tooLow.value.securityConfig.magicLinkTokenTtlMinutes)

        val inRange = callUpdateSettings(magicLinkTokenTtlMinutes = 45)
        assertIs<AdminResult.Success<Tenant>>(inRange)
        assertEquals(45, inRange.value.securityConfig.magicLinkTokenTtlMinutes)
    }

    // =========================================================================
    // Password-login toggle — passwordless-only enforcement
    // =========================================================================

    @Test
    fun `createUser non-invite path rejects when password login is disabled`() {
        tenants.clear()
        tenants.add(tenant.copy(securityConfig = tenant.securityConfig.copy(passwordLoginEnabled = false)))
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "bob",
                email = "bob@example.com",
                fullName = "Bob",
                password = "any-password",
                sendInvite = false,
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `createUser invite path still works when password login is disabled`() {
        tenants.clear()
        tenants.add(
            tenant.copy(
                securityConfig =
                    tenant.securityConfig.copy(
                        passwordLoginEnabled = false,
                        magicLinkEnabled = true,
                    ),
            ),
        )
        val result =
            userSvc.createUser(
                tenantId = TenantId(1),
                username = "carol",
                email = "carol@example.com",
                fullName = "Carol",
                sendInvite = true,
                baseUrl = "https://example.com",
            )
        assertIs<AdminResult.Success<User>>(result)
    }

    @Test
    fun `setTemporaryPassword rejects when password login is disabled`() {
        tenants.clear()
        tenants.add(
            tenant.copy(
                securityConfig =
                    tenant.securityConfig.copy(
                        passwordLoginEnabled = false,
                        magicLinkEnabled = true,
                    ),
            ),
        )
        users.add(alice)
        val result = accountSvc.setTemporaryPassword(UserId(10), TenantId(1))
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings rejects disabling password login on master tenant`() {
        tenants.clear()
        tenants.add(tenant.copy(slug = Tenant.MASTER_SLUG))
        val result =
            callUpdateSettings(slug = Tenant.MASTER_SLUG, passwordLoginEnabled = false, magicLinkEnabled = true)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings rejects disabling both password and magic link`() {
        val result = callUpdateSettings(passwordLoginEnabled = false, magicLinkEnabled = false)
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateWorkspaceSettings allows passwordless when magic link is enabled`() {
        val result = callUpdateSettings(passwordLoginEnabled = false, magicLinkEnabled = true)
        assertIs<AdminResult.Success<Tenant>>(result)
    }

    @Test
    fun `updateWorkspaceSettings fires ADMIN_SECURITY_CONFIG_UPDATED when toggle changes`() {
        callUpdateSettings(passwordLoginEnabled = false, magicLinkEnabled = true)
        assertTrue(auditLog.hasEvent(AuditEventType.ADMIN_SECURITY_CONFIG_UPDATED))
    }

    @Test
    fun `updateWorkspaceSettings does not fire ADMIN_SECURITY_CONFIG_UPDATED when toggle unchanged`() {
        callUpdateSettings(passwordLoginEnabled = true)
        assertTrue(!auditLog.hasEvent(AuditEventType.ADMIN_SECURITY_CONFIG_UPDATED))
    }

    @Test
    fun `updateEmailBranding rejects an invalid hex color`() {
        val result =
            wsSvc.updateEmailBranding(
                tenant.slug,
                com.kauth.domain.model.TenantEmailBranding(
                    tenantId = tenant.id,
                    brandColorHex = "not-a-color",
                ),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateEmailBranding rejects a support email without an at sign`() {
        val result =
            wsSvc.updateEmailBranding(
                tenant.slug,
                com.kauth.domain.model.TenantEmailBranding(
                    tenantId = tenant.id,
                    supportEmail = "support-without-at",
                ),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.Validation>(result.error)
    }

    @Test
    fun `updateEmailBranding returns NotFound for unknown tenant slug`() {
        val result =
            wsSvc.updateEmailBranding(
                "nope",
                com.kauth.domain.model
                    .TenantEmailBranding(tenantId = TenantId(99)),
            )
        assertIs<AdminResult.Failure>(result)
        assertIs<AdminError.NotFound>(result.error)
    }

    @Test
    fun `updateEmailBranding persists sanitised fields on success`() {
        val result =
            wsSvc.updateEmailBranding(
                tenant.slug,
                com.kauth.domain.model.TenantEmailBranding(
                    tenantId = tenant.id,
                    brandName = "  Acme  ",
                    brandColorHex = "#1FBCFF",
                    supportEmail = "support@acme.com",
                    fromDisplayName = "  Acme Support  ",
                ),
            )
        assertIs<AdminResult.Success<com.kauth.domain.model.TenantEmailBranding>>(result)
        val saved = emailBranding.findByTenantId(tenant.id)!!
        assertEquals("Acme", saved.brandName)
        assertEquals("Acme Support", saved.fromDisplayName)
    }

    @Test
    fun `updateEmailBranding is a soft no-op when the repository is not wired`() {
        val withoutRepo =
            WorkspaceSettingsService(
                tenantRepository = tenants,
                auditLog = auditLog,
            )
        val result =
            withoutRepo.updateEmailBranding(
                tenant.slug,
                com.kauth.domain.model
                    .TenantEmailBranding(tenantId = tenant.id),
            )
        assertIs<AdminResult.Success<com.kauth.domain.model.TenantEmailBranding>>(result)
    }
}
