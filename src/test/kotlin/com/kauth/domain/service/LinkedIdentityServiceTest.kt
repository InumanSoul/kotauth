package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.FactorChange
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.model.WebAuthnCredential
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.fakes.FakeWebAuthnCredentialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkedIdentityServiceTest {
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val links = FakeSocialAccountRepository()
    private val providers = FakeIdentityProviderRepository()
    private val passkeys = FakeWebAuthnCredentialRepository()
    private val audit = FakeAuditLogPort()
    private val emails = FakeEmailPort()

    private val tenantId = TenantId(1)
    private val userId = UserId(10)
    private val admin = UserId(1)
    private val oriana = ProviderKey.of("oriana")!!

    private val service =
        LinkedIdentityService(
            socialAccounts = links,
            users = users,
            tenants = tenants,
            identityProviders = providers,
            passkeys = passkeys,
            auditLog = audit,
            factorChangeNotifier =
                FactorChangeNotifier(users, tenants, emails, CoroutineScope(Dispatchers.Unconfined)),
        )

    @BeforeTest
    fun setUp() {
        tenants.clear()
        users.clear()
        links.clear()
        providers.clear()
        passwordless()
        users.add(socialOnlyUser())
        providers.add(provider(ProviderKey.GOOGLE))
        providers.add(provider(oriana))
        link(ProviderKey.GOOGLE)
    }

    @Test
    fun `a user cannot remove the only way they have to sign in`() {
        val result = service.unlinkOwn(tenantId, userId, ProviderKey.GOOGLE, ipAddress = null)

        assertEquals(UnlinkResult.OnlyWayToSignIn, result)
        assertEquals(1, links.findByUserId(userId).size)
        assertFalse(audit.hasEvent(AuditEventType.IDENTITY_UNLINKED))
    }

    @Test
    fun `a user with a second usable link can remove the first, and hears about it`() {
        link(oriana)

        val result = service.unlinkOwn(tenantId, userId, ProviderKey.GOOGLE, ipAddress = "203.0.113.9")

        assertEquals(UnlinkResult.Unlinked, result)
        assertEquals(listOf(oriana), links.findByUserId(userId).map { it.provider })
        val event = audit.events.single { it.eventType == AuditEventType.IDENTITY_UNLINKED }
        assertEquals("google", event.details["provider"])
        assertEquals("203.0.113.9", event.ipAddress)
        assertEquals(FactorChange.IDENTITY_UNLINKED, emails.sent.single().change)
    }

    @Test
    fun `a second link through a disabled provider is no way in`() {
        providers.clear()
        providers.add(provider(ProviderKey.GOOGLE))
        providers.add(provider(oriana).copy(enabled = false))
        link(oriana)

        assertTrue(service.isOnlyWayToSignIn(tenantId, userId, ProviderKey.GOOGLE))
    }

    @Test
    fun `a password, a passkey or email sign-in each count as another way in`() {
        tenants.add(workspace(SecurityConfig(passwordLoginEnabled = true)))
        users.update(socialOnlyUser().copy(passwordHash = "hashed:pw"))
        assertFalse(service.isOnlyWayToSignIn(tenantId, userId, ProviderKey.GOOGLE), "password")

        users.update(socialOnlyUser())
        assertTrue(service.isOnlyWayToSignIn(tenantId, userId, ProviderKey.GOOGLE), "no password set yet")

        passwordless(SecurityConfig(passwordLoginEnabled = false, emailOtpLoginEnabled = true))
        assertFalse(service.isOnlyWayToSignIn(tenantId, userId, ProviderKey.GOOGLE), "email code")

        passwordless()
        passkeys.save(passkey())
        assertFalse(service.isOnlyWayToSignIn(tenantId, userId, ProviderKey.GOOGLE), "passkey")
    }

    @Test
    fun `an administrator can remove the last link, and the user is told it was an administrator`() {
        val result = service.unlinkByAdmin(tenantId, userId, ProviderKey.GOOGLE, admin, ipAddress = null)

        assertEquals(UnlinkResult.Unlinked, result)
        assertTrue(links.findByUserId(userId).isEmpty())
        val event = audit.events.single { it.eventType == AuditEventType.ADMIN_IDENTITY_UNLINKED }
        assertEquals(admin, event.userId)
        assertEquals("10", event.details["targetUser"])
        assertEquals(FactorChange.IDENTITY_UNLINKED_BY_ADMIN, emails.sent.single().change)
    }

    @Test
    fun `removing a link that is not there changes nothing`() {
        assertEquals(UnlinkResult.NotLinked, service.unlinkOwn(tenantId, userId, oriana, null))
        assertEquals(
            UnlinkResult.NotLinked,
            service.unlinkByAdmin(TenantId(2), userId, ProviderKey.GOOGLE, admin, null),
        )
        assertEquals(1, links.findByUserId(userId).size)
        assertTrue(audit.events.isEmpty())
        assertTrue(emails.sent.isEmpty())
    }

    private fun passwordless(security: SecurityConfig = SecurityConfig(passwordLoginEnabled = false)) {
        tenants.add(workspace(security))
    }

    private fun workspace(security: SecurityConfig) =
        Tenant(
            id = tenantId,
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            securityConfig = security,
            passkeysEnabled = true,
            smtpEnabled = true,
            smtpHost = "smtp.example.com",
            smtpFromAddress = "noreply@acme.com",
        )

    private fun socialOnlyUser() =
        User(
            id = userId,
            tenantId = tenantId,
            username = "ada",
            email = "ada@acme.test",
            fullName = "Ada",
            passwordHash = User.SENTINEL_PASSWORD_HASH,
            enabled = true,
        )

    private fun provider(key: ProviderKey) =
        IdentityProvider(tenantId = tenantId, provider = key, clientId = "c", clientSecret = "s")

    private fun link(key: ProviderKey) =
        links.save(
            SocialAccount(
                userId = userId,
                tenantId = tenantId,
                provider = key,
                providerUserId = "${key.value}-ada",
                providerEmail = "ada@acme.test",
                providerName = "Ada",
            ),
        )

    private fun passkey() =
        WebAuthnCredential(
            userId = userId,
            tenantId = tenantId,
            credentialId = "cred-ada",
            publicKeyCose = ByteArray(77) { it.toByte() },
            signCounter = 0,
            aaguid = null,
            transports = listOf("internal"),
            name = "Key",
            backupEligible = false,
            backupState = false,
            createdAt = Instant.EPOCH,
            lastUsedAt = null,
        )
}
