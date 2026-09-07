package com.kauth.domain.service

import com.kauth.config.StaticSocialProviderResolver
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BrokeredReferenceHasher
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JIT branch inside [SocialLoginService.handleCallback].
 *
 * These tests pin where the branch sits: after [SocialLoginService] has resolved an existing user
 * and found none. An existing local user must keep governing the outcome even when JIT would
 * happily provision the same address.
 */
class SocialLoginJitBranchTest {
    private val idpRepo = FakeIdentityProviderRepository()
    private val socialAccounts = FakeSocialAccountRepository()
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val sessions = FakeSessionRepository()
    private val tokens = FakeTokenPort()
    private val hasher = FakePasswordHasher()
    private val auditLog = FakeAuditLogPort()

    private val oriana = requireNotNull(ProviderKey.of("oriana"))
    private val adapter = FakeSocialProviderPort(oriana)

    private val jit =
        JitProvisioningService(
            userRepository = users,
            socialAccountRepository = socialAccounts,
            auditLog = auditLog,
            references = BrokeredReferenceHasher("test-instance-secret-key-0123456789"),
        )

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
            providerResolver = StaticSocialProviderResolver(mapOf(oriana to adapter)),
            collisionCheck = IdentifierCollisionCheck(users),
            jitProvisioning = jit,
        )

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private fun idp(
        jitEnabled: Boolean,
        domains: List<String> = listOf("oriana.com.py"),
        trustEmail: Boolean = false,
    ) = IdentityProvider(
        tenantId = tenant.id,
        provider = oriana,
        clientId = "oriana-client-id",
        clientSecret = "oriana-secret",
        enabled = true,
        kind = ProviderKind.OIDC,
        issuer = "https://idp.oriana.com.py",
        jitEnabled = jitEnabled,
        jitAllowedDomains = domains,
        trustEmailClaim = trustEmail,
    )

    private fun profile(
        email: String,
        verified: Boolean = true,
        providerUserId: String = "oriana-sub-1",
    ) = SocialUserProfile(
        providerUserId = providerUserId,
        email = email,
        name = "Ada Lovelace",
        emailVerified = verified,
    )

    private val existingAda =
        User(
            id = UserId(10),
            tenantId = tenant.id,
            username = "ada",
            email = "ada@oriana.com.py",
            fullName = "Ada The Local",
            passwordHash = "hashed:pass",
            emailVerified = true,
            enabled = true,
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
        adapter.clear()
        tenants.add(tenant)
    }

    // =========================================================================
    // Ordering: an existing local user always wins over JIT
    // =========================================================================

    @Test
    fun `an existing local user with the same email is linked, never re-provisioned`() {
        idpRepo.add(idp(jitEnabled = true))
        users.add(existingAda)
        adapter.profileToReturn = profile(email = "ada@oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val success = assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals(UserId(10), success.value.user.id, "The existing local user must be the one logged in")
        assertEquals("ada", success.value.user.username, "JIT must not have created a second ada@oriana.com.py")
        assertEquals(false, success.value.isNewUser)
        assertEquals(1, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED))
    }

    @Test
    fun `an already linked user is logged in without consulting the JIT gate`() {
        idpRepo.add(idp(jitEnabled = true))
        users.add(existingAda)
        socialAccounts.save(
            SocialAccount(
                userId = UserId(10),
                tenantId = tenant.id,
                provider = oriana,
                providerUserId = "oriana-sub-1",
                providerEmail = "ada@oriana.com.py",
                providerName = "Ada",
            ),
        )
        adapter.profileToReturn = profile(email = "ada@oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val success = assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals(UserId(10), success.value.user.id)
        assertEquals(1, users.findByTenantId(tenant.id, null, 100, 0).size)
    }

    @Test
    fun `an unverified email colliding with a local user is refused, not provisioned`() {
        idpRepo.add(idp(jitEnabled = true))
        users.add(existingAda)
        adapter.profileToReturn = profile(email = "ada@oriana.com.py", verified = false)

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val failure = assertIs<SocialLoginResult.Failure>(result)
        assertEquals(SocialLoginError.LinkRequiresEmailVerification, failure.error)
        assertEquals(1, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED))
    }

    @Test
    fun `trusting the claim lets an unverified sign-in adopt the matching local account`() {
        // The consequential half of the switch: no domain list narrows this path, so the
        // operator is asserting that this issuer's address claim can be relied on.
        idpRepo.add(idp(jitEnabled = true, trustEmail = true))
        users.add(existingAda)
        adapter.profileToReturn = profile(email = "ada@oriana.com.py", verified = false)

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val success = assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals(existingAda.id, success.value.user.id)
        assertEquals(
            1,
            users.findByTenantId(tenant.id, null, 100, 0).size,
            "The local account is adopted, not duplicated",
        )
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED), "Adopting is not provisioning")
    }

    // =========================================================================
    // The branch itself
    // =========================================================================

    @Test
    fun `an unknown user on an allowed domain is provisioned and logged straight in`() {
        idpRepo.add(idp(jitEnabled = true))
        adapter.profileToReturn = profile(email = "grace@oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost", "1.2.3.4")

        val success = assertIs<SocialLoginResult.Success<SocialLoginSuccess>>(result)
        assertEquals("grace@oriana.com.py", success.value.user.username)
        assertNull(success.value.user.externalId)
        assertTrue(success.value.isNewUser)
        assertEquals(1, sessions.all().size)
        assertTrue(auditLog.hasEvent(AuditEventType.JIT_USER_PROVISIONED))
        assertTrue(auditLog.hasEvent(AuditEventType.LOGIN_SUCCESS))
    }

    @Test
    fun `a refused gate falls through to registration and carries the reason outward`() {
        idpRepo.add(idp(jitEnabled = true))
        adapter.profileToReturn = profile(email = "mallory@evil-oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val needs = assertIs<SocialLoginResult.NeedsRegistration>(result)
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, needs.data.jitRefusal)
        assertEquals(0, users.findByTenantId(tenant.id, null, 100, 0).size)
    }

    @Test
    fun `an unverified unknown user falls through carrying the verification refusal`() {
        idpRepo.add(idp(jitEnabled = true))
        adapter.profileToReturn = profile(email = "grace@oriana.com.py", verified = false)

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val needs = assertIs<SocialLoginResult.NeedsRegistration>(result)
        assertEquals(JitRefusal.EMAIL_NOT_VERIFIED, needs.data.jitRefusal)
        assertEquals(0, users.findByTenantId(tenant.id, null, 100, 0).size)
    }

    // `findByEmail` misses because the local account's *email* differs; its *username* is the
    // asserted address. The gate must turn that into a refusal the operator can see rather than
    // an exception that escapes the domain.
    @Test
    fun `an address held as another account's username falls through instead of throwing`() {
        idpRepo.add(idp(jitEnabled = true))
        users.add(
            User(
                id = UserId(11),
                tenantId = tenant.id,
                username = "grace@oriana.com.py",
                email = "grace.hopper@oriana.com.py",
                fullName = "Grace The Local",
                passwordHash = "hashed:pass",
                emailVerified = true,
                enabled = true,
            ),
        )
        adapter.profileToReturn = profile(email = "grace@oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val needs = assertIs<SocialLoginResult.NeedsRegistration>(result)
        assertEquals(JitRefusal.USERNAME_CONFLICT, needs.data.jitRefusal)
        assertEquals(1, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED))
    }

    @Test
    fun `a provider without JIT behaves exactly as before`() {
        idpRepo.add(idp(jitEnabled = false))
        adapter.profileToReturn = profile(email = "grace@oriana.com.py")

        val result = svc.handleCallback("acme", oriana, "code", "http://localhost")

        val needs = assertIs<SocialLoginResult.NeedsRegistration>(result)
        assertNull(needs.data.jitRefusal)
        assertEquals("grace@oriana.com.py", needs.data.email)
        assertEquals(0, users.findByTenantId(tenant.id, null, 100, 0).size)
    }
}
