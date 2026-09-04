package com.kauth.domain.service

import com.kauth.config.StaticSocialProviderResolver
import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePasswordHasher
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeSessionRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeTokenPort
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that a client's configured default roles are granted to users who
 * self-register through that client — across both the password-registration
 * (`AuthService.register`) and social-registration
 * (`SocialLoginService.completeSocialRegistration`) paths.
 */
class ClientDefaultRolesRegistrationTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val roles = FakeRoleRepository()
    private val apps = FakeApplicationRepository()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()
    private val sessions = FakeSessionRepository()
    private val tokens = FakeTokenPort()
    private val socialAccounts = FakeSocialAccountRepository()
    private val idpRepo = FakeIdentityProviderRepository()

    private val authService =
        AuthService(
            userRepository = users,
            tenantRepository = tenants,
            tokenPort = tokens,
            passwordHasher = hasher,
            auditLog = auditLog,
            sessionRepository = sessions,
            applicationRepository = apps,
            roleRepository = roles,
            identifierResolver = UserIdentifierResolver(users),
            collisionCheck = IdentifierCollisionCheck(users),
        )

    private val socialService =
        SocialLoginService(
            identityProviderRepository = idpRepo,
            socialAccountRepository = socialAccounts,
            userRepository = users,
            tenantRepository = tenants,
            sessionRepository = sessions,
            tokenPort = tokens,
            passwordHasher = hasher,
            auditLog = auditLog,
            providerResolver = StaticSocialProviderResolver(emptyMap()),
            applicationRepository = apps,
            roleRepository = roles,
        )

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            registrationEnabled = true,
        )

    private lateinit var onboardingApp: Application
    private lateinit var applicantRole: Role

    @BeforeTest
    fun setUp() {
        tenants.clear()
        users.clear()
        roles.clear()
        apps.clear()
        auditLog.clear()
        sessions.clear()
        tokens.reset()
        socialAccounts.clear()
        idpRepo.clear()

        tenants.add(tenant)
        onboardingApp =
            apps.create(
                tenantId = tenant.id,
                clientId = "onboarding-spa",
                name = "Onboarding",
                description = null,
                accessType = AccessType.PUBLIC.value,
                redirectUris = listOf("https://onboarding.acme.test/cb"),
                grantTypes = GrantType.defaultsFor(AccessType.PUBLIC),
                clientSecretHash = null,
                audience = null,
            )
        applicantRole =
            roles.add(Role(tenantId = tenant.id, name = "onboarding.applicant", scope = RoleScope.TENANT))
    }

    private fun register(originatingClientId: String?) =
        authService.register(
            tenantSlug = "acme",
            username = "newuser",
            email = "newuser@acme.test",
            fullName = "New User",
            rawPassword = "Sufficiently-long-pass-1",
            confirmPassword = "Sufficiently-long-pass-1",
            baseUrl = "https://acme.test",
            originatingClientId = originatingClientId,
        )

    @Test
    fun `password registration through a client grants that client's default roles`() {
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        val result = register(originatingClientId = "onboarding-spa")

        assertIs<AuthResult.Success<*>>(result)
        val user = (result as AuthResult.Success).value
        assertEquals(
            listOf("onboarding.applicant"),
            roles.findRolesForUser(user.id!!).map { it.name },
        )
    }

    @Test
    fun `registration without an originating client grants nothing`() {
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        val result = register(originatingClientId = null)

        val user = (result as AuthResult.Success).value
        assertTrue(roles.findRolesForUser(user.id!!).isEmpty())
    }

    @Test
    fun `registration through an unknown client grants nothing and still succeeds`() {
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        val result = register(originatingClientId = "no-such-client")

        assertIs<AuthResult.Success<*>>(result)
        val user = (result as AuthResult.Success).value
        assertTrue(roles.findRolesForUser(user.id!!).isEmpty())
    }

    @Test
    fun `registration through a client with no defaults configured grants nothing`() {
        val result = register(originatingClientId = "onboarding-spa")

        val user = (result as AuthResult.Success).value
        assertTrue(roles.findRolesForUser(user.id!!).isEmpty())
    }

    @Test
    fun `default roles are granted even when the tenant requires email verification`() {
        tenants.clear()
        tenants.add(tenant.copy(emailVerificationRequired = true))
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        val result = register(originatingClientId = "onboarding-spa")

        val user = (result as AuthResult.Success).value
        assertEquals(false, user.emailVerified)
        assertEquals(1, roles.findRolesForUser(user.id!!).size)
    }

    @Test
    fun `social registration through a client grants that client's default roles`() {
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        val result =
            socialService.completeSocialRegistration(
                tenantSlug = "acme",
                provider = ProviderKey.GOOGLE,
                providerUserId = "google-uid-1",
                email = "social@acme.test",
                providerName = "Social User",
                avatarUrl = null,
                emailVerified = true,
                chosenUsername = "socialuser",
                originatingClientId = "onboarding-spa",
            )

        assertIs<SocialLoginResult.Success<*>>(result)
        val created = users.findByEmail(tenant.id, "social@acme.test")!!
        assertEquals(
            listOf("onboarding.applicant"),
            roles.findRolesForUser(created.id!!).map { it.name },
        )
    }

    @Test
    fun `social registration without an originating client grants nothing`() {
        roles.setDefaultRolesForClient(onboardingApp.id, listOf(applicantRole.id!!))

        socialService.completeSocialRegistration(
            tenantSlug = "acme",
            provider = ProviderKey.GOOGLE,
            providerUserId = "google-uid-2",
            email = "social2@acme.test",
            providerName = "Social Two",
            avatarUrl = null,
            emailVerified = true,
            chosenUsername = "socialtwo",
            originatingClientId = null,
        )

        val created = users.findByEmail(tenant.id, "social2@acme.test")!!
        assertTrue(roles.findRolesForUser(created.id!!).isEmpty())
    }
}
