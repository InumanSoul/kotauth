package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BrokeredReferenceHasher
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.port.SocialUserProfile
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeSocialAccountRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [JitProvisioningService] — the trust gate that decides whether a first sign-in
 * through a brokered provider becomes an account.
 */
class JitProvisioningServiceTest {
    private val users = FakeUserRepository()
    private val socialAccounts = FakeSocialAccountRepository()
    private val auditLog = FakeAuditLogPort()

    private val service =
        JitProvisioningService(
            userRepository = users,
            socialAccountRepository = socialAccounts,
            auditLog = auditLog,
            references = BrokeredReferenceHasher("test-instance-secret-key-0123456789"),
        )

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
        )

    private val oriana = requireNotNull(ProviderKey.of("oriana"))

    private fun provider(
        jit: Boolean,
        domains: List<String>,
    ) = IdentityProvider(
        tenantId = tenant.id,
        provider = oriana,
        clientId = "oriana-client-id",
        clientSecret = "oriana-secret",
        kind = ProviderKind.OIDC,
        issuer = "https://idp.oriana.com.py",
        jitEnabled = jit,
        jitAllowedDomains = domains,
    )

    private fun profile(
        email: String?,
        verified: Boolean,
        name: String? = "Ada Lovelace",
        givenName: String? = null,
        familyName: String? = null,
        providerUserId: String = "oriana-sub-1",
    ) = SocialUserProfile(
        providerUserId = providerUserId,
        email = email,
        name = name,
        emailVerified = verified,
        avatarUrl = null,
        givenName = givenName,
        familyName = familyName,
    )

    @BeforeTest
    fun setup() {
        users.clear()
        socialAccounts.clear()
        auditLog.clear()
    }

    // =========================================================================
    // The gate
    // =========================================================================

    @Test
    fun `a verified email on an allowed domain is provisioned`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        val user = (outcome as JitOutcome.Provisioned).user
        assertEquals("ada@oriana.com.py", user.username) // matches SCIM's userName, not a local part
        assertNull(user.externalId) // SCIM's column, not ours
    }

    @Test
    fun `a lookalike domain is refused, not matched by suffix`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "mallory@evil-oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `a subdomain of an allowed domain is refused`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "mallory@mail.oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `an allowed domain that is a suffix of the asserted one is refused`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("com.py")),
                profile(email = "mallory@oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    // U+0131 LATIN SMALL LETTER DOTLESS I. `equalsIgnoreCase` calls this the allowed domain; an
    // exact comparison over the A-label does not.
    @Test
    fun `a dotless-i lookalike domain is refused where ignoring case would have matched`() {
        assertTrue(
            "oriana.com.py".equals("or\u0131ana.com.py", ignoreCase = true),
            "The premise of this test is that the JDK folds these two together",
        )
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "mallory@or\u0131ana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `an allowed domain spelled with a dotless i does not admit the ASCII one`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("or\u0131ana.com.py")),
                profile(email = "mallory@oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    // U+212A KELVIN SIGN lower-cases to 'k', so it spells the allowed domain rather than looking
    // like it — dropping `ignoreCase` must not start refusing it.
    @Test
    fun `a Kelvin sign in the asserted domain still matches the allowed one`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("kelvin.example")),
                profile(email = "ada@\u212Aelvin.example", verified = true),
            )
        assertIs<JitOutcome.Provisioned>(outcome)
    }

    @Test
    fun `an allowed unicode domain matches the same domain asserted as punycode`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("or\u0131ana.com.py")),
                profile(email = "ada@xn--orana-o4a.com.py", verified = true),
            )
        assertIs<JitOutcome.Provisioned>(outcome)
    }

    // Delete the single-'@' guard and `substringAfterLast` reads the tail of this address as the
    // allowed domain, provisioning an account for a mailbox at evil.example.
    @Test
    fun `an address with two at-signs is refused rather than read as its tail`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "mallory@evil.example@oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
        assertEquals(0, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, socialAccounts.all().size)
    }

    @Test
    fun `a refused two-at-sign address leaves no email domain on the diagnostics row`() {
        service.provision(
            tenant,
            provider(jit = true, domains = listOf("oriana.com.py")),
            profile(email = "mallory@evil.example@oriana.com.py", verified = true),
        )
        val event = auditLog.events.single { it.eventType == AuditEventType.SOCIAL_LOGIN_FAILED }
        assertNull(
            event.details["email_domain"],
            "An ambiguous address must not have its tail recorded as the domain that was refused",
        )
    }

    // The username is the address, and an admin-created username may contain '@'. Without the
    // pre-check this insert violates UNIQUE (tenant_id, username): an exception, not a result, on
    // every retry, and no diagnostics row at all.
    @Test
    fun `an email already held as another account's username is a refusal, not an exception`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "ada@oriana.com.py",
                email = "ada.lovelace@oriana.com.py",
                fullName = "Ada The Local",
                passwordHash = "hashed:pass",
                emailVerified = true,
            ),
        )
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.USERNAME_CONFLICT, (outcome as JitOutcome.Refused).reason)
        assertEquals(1, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, socialAccounts.all().size)
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED))
    }

    @Test
    fun `a username conflict reaches the diagnostics panel with its own reason`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "ada@oriana.com.py",
                email = "ada.lovelace@oriana.com.py",
                fullName = "Ada The Local",
                passwordHash = "hashed:pass",
                emailVerified = true,
            ),
        )
        service.provision(
            tenant,
            provider(jit = true, domains = listOf("oriana.com.py")),
            profile(email = "ada@oriana.com.py", verified = true),
        )
        val event = auditLog.events.single { it.eventType == AuditEventType.SOCIAL_LOGIN_FAILED }
        assertEquals("username_conflict", event.details["reason"])
        assertEquals("oriana.com.py", event.details["email_domain"])
        assertNotNull(event.details["reference"])
    }

    @Test
    fun `a username taken in another workspace does not refuse this one`() {
        users.add(
            User(
                tenantId = TenantId(2),
                username = "ada@oriana.com.py",
                email = "ada@oriana.com.py",
                fullName = "Ada Elsewhere",
                passwordHash = "hashed:pass",
                emailVerified = true,
            ),
        )
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        assertIs<JitOutcome.Provisioned>(outcome)
    }

    @Test
    fun `an unverified email is refused even on an allowed domain`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = false),
            )
        assertEquals(JitRefusal.EMAIL_NOT_VERIFIED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `an empty allowed-domain list provisions nobody`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = emptyList()),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `jit disabled falls through rather than refusing`() {
        assertEquals(
            JitOutcome.NotEnabled,
            service.provision(
                tenant,
                provider(jit = false, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            ),
        )
    }

    @Test
    fun `jit disabled with an empty allowed list still falls through`() {
        assertEquals(
            JitOutcome.NotEnabled,
            service.provision(
                tenant,
                provider(jit = false, domains = emptyList()),
                profile(email = "ada@oriana.com.py", verified = true),
            ),
        )
    }

    @Test
    fun `domain matching ignores case on both sides`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "Ada@ORIANA.COM.PY", verified = true),
            )
        val user = assertIs<JitOutcome.Provisioned>(outcome).user
        assertEquals("ada@oriana.com.py", user.email)
        assertEquals("ada@oriana.com.py", user.username)
    }

    @Test
    fun `a second allowed domain in the list is honoured`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("other.example", "oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        assertIs<JitOutcome.Provisioned>(outcome)
    }

    @Test
    fun `an address with no domain part is refused`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "not-an-address", verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `a null email is refused rather than provisioned`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = null, verified = true),
            )
        assertEquals(JitRefusal.DOMAIN_NOT_ALLOWED, (outcome as JitOutcome.Refused).reason)
    }

    @Test
    fun `a refusal creates no user and no link`() {
        service.provision(
            tenant,
            provider(jit = true, domains = listOf("oriana.com.py")),
            profile(email = "mallory@evil-oriana.com.py", verified = true),
        )
        assertEquals(0, users.findByTenantId(tenant.id, null, 100, 0).size)
        assertEquals(0, socialAccounts.all().size)
        assertEquals(0, auditLog.countOf(AuditEventType.JIT_USER_PROVISIONED))
    }

    // =========================================================================
    // The created record
    // =========================================================================

    @Test
    fun `the created record carries the SCIM-compatible shape`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true, givenName = "Ada", familyName = "Lovelace"),
            )
        val user = assertIs<JitOutcome.Provisioned>(outcome).user
        assertEquals(tenant.id, user.tenantId)
        assertEquals("ada@oriana.com.py", user.username)
        assertEquals("ada@oriana.com.py", user.email)
        assertEquals("Ada Lovelace", user.fullName)
        assertEquals("Ada", user.givenName)
        assertEquals("Lovelace", user.familyName)
        assertNull(user.externalId)
        assertEquals(User.SENTINEL_PASSWORD_HASH, user.passwordHash)
        assertTrue(user.emailVerified)
        assertTrue(user.enabled)
        assertTrue(user.requiredActions.isEmpty())
    }

    @Test
    fun `the created record is persisted with an id`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        val user = assertIs<JitOutcome.Provisioned>(outcome).user
        assertEquals(user, users.findByEmail(tenant.id, "ada@oriana.com.py"))
        assertEquals(user.id, users.findByEmail(tenant.id, "ada@oriana.com.py")?.id)
    }

    @Test
    fun `the full name falls back to the name parts, then to the email local part`() {
        val fromParts =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true, name = null, givenName = "Ada", familyName = "L"),
            )
        assertEquals("Ada L", assertIs<JitOutcome.Provisioned>(fromParts).user.fullName)

        val fromLocalPart =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(
                    email = "grace@oriana.com.py",
                    verified = true,
                    name = "   ",
                    providerUserId = "oriana-sub-2",
                ),
            )
        assertEquals("grace", assertIs<JitOutcome.Provisioned>(fromLocalPart).user.fullName)
    }

    @Test
    fun `the provider identity is linked in social accounts, not in external id`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
            )
        val user = assertIs<JitOutcome.Provisioned>(outcome).user
        val link = socialAccounts.findByProviderIdentity(tenant.id, oriana, "oriana-sub-1")
        assertEquals(user.id, link?.userId)
        assertEquals(tenant.id, link?.tenantId)
        assertEquals("ada@oriana.com.py", link?.providerEmail)
        assertNull(user.externalId)
    }

    @Test
    fun `every provisioning writes an audit event naming the tenant, provider, sub and user`() {
        val outcome =
            service.provision(
                tenant,
                provider(jit = true, domains = listOf("oriana.com.py")),
                profile(email = "ada@oriana.com.py", verified = true),
                ipAddress = "1.2.3.4",
                userAgent = "curl/8",
            )
        val user = assertIs<JitOutcome.Provisioned>(outcome).user
        val event = auditLog.events.single { it.eventType == AuditEventType.JIT_USER_PROVISIONED }
        assertEquals(tenant.id, event.tenantId)
        assertEquals(user.id, event.userId)
        assertEquals("oriana", event.details["provider"])
        assertEquals("oriana-sub-1", event.details["provider_user_id"])
        assertEquals("1.2.3.4", event.ipAddress)
    }
}
