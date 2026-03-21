package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.SocialProvider
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.User
import com.kauth.domain.port.SocialUserProfile
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeSocialProviderPort
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SocialLoginService].
 *
 * Covers: buildRedirectUrl, handleCallback, completeSocialRegistration.
 * All provider HTTP calls replaced by FakeSocialProviderPort.
 */
class SocialLoginServiceTest {
    private val idpRepo = FakeIdentityProviderRepository()
    private val socialAccounts = FakeSocialAccountRepository()
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val sessions = FakeSessionRepository()
    private val tokens = FakeTokenPort()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val googleAdapter = FakeSocialProviderPort(SocialProvider.GOOGLE)
    private val githubAdapter = FakeSocialProviderPort(SocialProvider.GITHUB)

    private val svc =
        SocialLoginService(
            identityProviderRepository = idpRepo,
            socialAccountRepository = socialAccounts,
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            tokenPort = tokens,
            passwordHasher = hasher,
            auditLog = auditLog,
            providerAdapters =
                mapOf(
                    SocialProvider.GOOGLE to googleAdapter,
                    SocialProvider.GITHUB to githubAdapter,
                ),
        )

    private val tenant =
        Tenant(
            id = 1,
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            registrationEnabled = true,
        )

    private val googleIdp =
        IdentityProvider(
            tenantId = 1,
            provider = SocialProvider.GOOGLE,
            clientId = "google-client-id",
            clientSecret = "google-secret",
            enabled = true,
        )

    private val alice
        get() =
            User(
                id = 10,
                tenantId = 1,
                username = "alice",
                email = "alice@example.com",
                fullName = "Alice Test",
                passwordHash = hasher.hash("pass"),
                emailVerified = true,
                enabled = true,
            )

    private val disabledUser
        get() =
            User(
                id = 11,
                tenantId = 1,
                username = "disabled",
                email = "disabled@example.com",
                fullName = "Disabled",
                passwordHash = hasher.hash("pass"),
                enabled = false,
            )

    private val googleProfile =
        SocialUserProfile(
            providerUserId = "google-uid-123",
            email = "alice@example.com",
            name = "Alice Google",
            emailVerified = true,
            avatarUrl = "https://avatar.example.com/alice.jpg",
        )

    @BeforeTest
    fun setup() {
        idpRepo.clear()
        socialAccounts.clear()
        users.clear()
        tenants.clear()
        sessions.clear()
        tokens.reset()
        auditLog.clear()
        googleAdapter.clear()
        githubAdapter.clear()
        tenants.add(tenant)
        idpRepo.add(googleIdp)
        users.add(alice)
        users.add(disabledUser)
        googleAdapter.profileToReturn = googleProfile
    }

    // =========================================================================
    // buildRedirectUrl
    // =========================================================================

    @Test
    fun `buildRedirectUrl - tenant not found`() {
        val result = svc.buildRedirectUrl("unknown", SocialProvider.GOOGLE, "state123", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.TenantNotFound, result.error)
    }

    @Test
    fun `buildRedirectUrl - provider not configured for tenant`() {
        val result = svc.buildRedirectUrl("acme", SocialProvider.GITHUB, "state123", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.ProviderNotConfigured, result.error)
    }

    @Test
    fun `buildRedirectUrl - disabled provider`() {
        idpRepo.add(
            IdentityProvider(
                tenantId = 1,
                provider = SocialProvider.GITHUB,
                clientId = "gh-id",
                clientSecret = "gh-secret",
                enabled = false,
            ),
        )
        val result = svc.buildRedirectUrl("acme", SocialProvider.GITHUB, "state123", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.ProviderNotConfigured, result.error)
    }

    @Test
    fun `buildRedirectUrl - success returns authorization URL`() {
        val result = svc.buildRedirectUrl("acme", SocialProvider.GOOGLE, "state123", "http://localhost")
        assertIs<SocialLoginResult.Success<String>>(result)
        assertTrue(result.value.contains("client_id=google-client-id"))
        assertTrue(result.value.contains("state=state123"))
        assertTrue(result.value.contains("/t/acme/auth/social/google/callback"))
    }

    // =========================================================================
    // handleCallback
    // =========================================================================

    @Test
    fun `handleCallback - tenant not found`() {
        val result = svc.handleCallback("unknown", SocialProvider.GOOGLE, "code", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.TenantNotFound, result.error)
    }

    @Test
    fun `handleCallback - provider not configured`() {
        val result = svc.handleCallback("acme", SocialProvider.GITHUB, "code", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.ProviderNotConfigured, result.error)
    }

    @Test
    fun `handleCallback - provider exchange fails`() {
        googleAdapter.shouldFail = true
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "bad-code", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertIs<SocialLoginError.ProviderError>(result.error)
    }

    @Test
    fun `handleCallback - provider returns no email`() {
        googleAdapter.profileToReturn = googleProfile.copy(email = null)
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "code", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.EmailNotProvided, result.error)
    }

    @Test
    fun `handleCallback - existing user via email match auto-links and issues tokens`() {
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "code", "http://localhost", "1.2.3.4")
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals("alice", result.value.user.username)
        assertNotNull(result.value.tokens.access_token)
        assertEquals(1, socialAccounts.all().size, "Social account should be auto-linked")
        assertEquals(1, sessions.all().size)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_SUCCESS))
    }

    @Test
    fun `handleCallback - existing user via social account link`() {
        socialAccounts.save(
            com.kauth.domain.model.SocialAccount(
                userId = 10,
                tenantId = 1,
                provider = SocialProvider.GOOGLE,
                providerUserId = "google-uid-123",
                providerEmail = "alice@example.com",
                providerName = "Alice",
            ),
        )
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "code", "http://localhost")
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals("alice", result.value.user.username)
    }

    @Test
    fun `handleCallback - disabled user returns UserDisabled`() {
        googleAdapter.profileToReturn =
            googleProfile.copy(email = "disabled@example.com", providerUserId = "google-disabled")
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "code", "http://localhost")
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.UserDisabled, result.error)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_FAILED))
    }

    @Test
    fun `handleCallback - no existing user returns NeedsRegistration`() {
        googleAdapter.profileToReturn =
            SocialUserProfile(
                providerUserId = "new-google-uid",
                email = "newuser@example.com",
                name = "New User",
                emailVerified = true,
            )
        val result = svc.handleCallback("acme", SocialProvider.GOOGLE, "code", "http://localhost")
        assertIs<SocialLoginResult.NeedsRegistration>(result)
        assertEquals("newuser@example.com", result.data.email)
        assertEquals("new-google-uid", result.data.providerUserId)
        assertEquals(SocialProvider.GOOGLE, result.data.provider)
    }

    // =========================================================================
    // completeSocialRegistration
    // =========================================================================

    @Test
    fun `completeSocialRegistration - tenant not found`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "unknown",
                provider = SocialProvider.GOOGLE,
                providerUserId = "uid",
                email = "new@example.com",
                providerName = "New",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "newuser",
            )
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.TenantNotFound, result.error)
    }

    @Test
    fun `completeSocialRegistration - registration disabled`() {
        tenants.clear()
        tenants.add(tenant.copy(registrationEnabled = false))
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "uid",
                email = "new@example.com",
                providerName = "New",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "newuser",
            )
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.RegistrationDisabled, result.error)
    }

    @Test
    fun `completeSocialRegistration - username too short`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "uid",
                email = "new@example.com",
                providerName = "New",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "ab",
            )
        assertIs<SocialLoginResult.Failure>(result)
        assertIs<SocialLoginError.InvalidUsername>(result.error)
    }

    @Test
    fun `completeSocialRegistration - username with invalid characters`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "uid",
                email = "new@example.com",
                providerName = "New",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "bad user!",
            )
        assertIs<SocialLoginResult.Failure>(result)
        assertIs<SocialLoginError.InvalidUsername>(result.error)
    }

    @Test
    fun `completeSocialRegistration - race condition existing link reuses user`() {
        socialAccounts.save(
            com.kauth.domain.model.SocialAccount(
                userId = 10,
                tenantId = 1,
                provider = SocialProvider.GOOGLE,
                providerUserId = "race-uid",
                providerEmail = "alice@example.com",
                providerName = "Alice",
            ),
        )
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "race-uid",
                email = "alice@example.com",
                providerName = "Alice",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "newname",
            )
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals("alice", result.value.user.username, "Should reuse existing user, not create new")
    }

    @Test
    fun `completeSocialRegistration - race condition email match auto-links`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "new-uid",
                email = "alice@example.com",
                providerName = "Alice",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "newname",
            )
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals("alice", result.value.user.username, "Should auto-link to existing user by email")
        assertEquals(1, socialAccounts.all().size)
    }

    @Test
    fun `completeSocialRegistration - username conflict`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "brand-new-uid",
                email = "brand-new@example.com",
                providerName = "Brand New",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "alice", // already taken
            )
        assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.UsernameConflict, result.error)
    }

    @Test
    fun `completeSocialRegistration - success creates user and social link`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "new-uid-999",
                email = "brand-new@example.com",
                providerName = "Brand New User",
                avatarUrl = "https://avatar.example.com/new.jpg",
                emailVerified = true,
                chosenUsername = "brandnew",
                ipAddress = "1.2.3.4",
                userAgent = "TestAgent",
            )
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertTrue(result.value.isNewUser)
        assertEquals("brandnew", result.value.user.username)
        assertEquals("brand-new@example.com", result.value.user.email)
        assertEquals(true, result.value.user.emailVerified, "Provider-verified email should be trusted")
        assertNotNull(result.value.tokens.access_token)
        val newSocialAccount = socialAccounts.all().find { it.providerUserId == "new-uid-999" }
        assertNotNull(newSocialAccount, "Social account link should be created")
        assertEquals("brandnew", users.findById(newSocialAccount.userId)?.username)
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_SUCCESS))
        assertEquals(1, sessions.all().size)
    }

    @Test
    fun `completeSocialRegistration - unverified email propagated`() {
        val result =
            svc.completeSocialRegistration(
                tenantSlug = "acme",
                provider = SocialProvider.GOOGLE,
                providerUserId = "unverified-uid",
                email = "unverified@example.com",
                providerName = "Unverified",
                avatarUrl = null,
                emailVerified = false,
                chosenUsername = "unverified_user",
            )
        assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals(false, result.value.user.emailVerified, "Unverified email should not be marked as verified")
    }
}
