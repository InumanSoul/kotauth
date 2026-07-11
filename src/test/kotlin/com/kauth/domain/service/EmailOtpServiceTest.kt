package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.Role
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.util.sha256Hex
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailOtpChallengeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeResourceServerRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailOtpServiceTest {
    private val tenants = FakeTenantRepository()
    private val users = FakeUserRepository()
    private val challenges = FakeEmailOtpChallengeRepository()
    private val apps = FakeApplicationRepository()
    private val authCodes = FakeAuthorizationCodeRepository()
    private val resources = FakeResourceServerRepository()
    private val roles = FakeRoleRepository()
    private val email = FakeEmailPort()
    private val audit = FakeAuditLogPort()

    private val fixedClock = Clock.fixed(Instant.parse("2026-05-25T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var tenant: Tenant
    private lateinit var clientApp: Application

    private fun newService() =
        EmailOtpService(
            tenantRepository = tenants,
            userRepository = users,
            challengeRepository = challenges,
            applicationRepository = apps,
            authorizationCodeRepository = authCodes,
            emailPort = email,
            auditLog = audit,
            resourceServerRepository = resources,
            roleRepository = roles,
            clock = fixedClock,
        )

    @BeforeTest
    fun setUp() {
        tenants.clear()
        users.clear()
        challenges.clear()
        apps.clear()
        authCodes.clear()
        resources.clear()
        roles.clear()
        email.clear()
        audit.clear()

        tenant =
            tenants.add(
                Tenant(
                    id = TenantId(0),
                    slug = "acme",
                    displayName = "Acme",
                    issuerUrl = "https://acme.example.com/t/acme",
                    securityConfig = SecurityConfig(emailOtpSignupEnabled = true),
                ),
            )
        clientApp =
            apps.add(
                Application(
                    id = ApplicationId(0),
                    tenantId = tenant.id,
                    clientId = "acme-bff",
                    name = "Acme BFF",
                    description = null,
                    accessType = AccessType.CONFIDENTIAL,
                    enabled = true,
                    redirectUris = listOf("https://bff.acme.example.com/auth/callback"),
                ),
            )
        resources.registerClient(clientApp.id, clientApp.tenantId)
    }

    // ----- sendOtp -----

    @Test
    fun `sendOtp creates a challenge with hashed code for an existing user`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )

        val result = newService().sendOtp(tenant.slug, "alice@acme.test", clientApp.clientId)

        assertTrue(result is OtpSendResult.Success)
        val saved = challenges.findByChallengeId(result.challengeId)!!
        val sentCode = email.otps.single().code
        assertEquals(sha256Hex(sentCode), saved.codeHash)
        assertEquals(clientApp.clientId, saved.originatingClientId)
        assertEquals(AuditEventType.EMAIL_OTP_SENT, audit.events.single().eventType)
    }

    @Test
    fun `sendOtp stores normalized requested resources`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )

        val result =
            newService().sendOtp(
                tenant.slug,
                "alice@acme.test",
                clientApp.clientId,
                resources = listOf(" orders-api ", "", "orders-api"),
            )

        assertTrue(result is OtpSendResult.Success)
        val saved = challenges.findByChallengeId(result.challengeId)!!
        assertEquals(listOf("orders-api"), saved.resources)
        assertEquals("[\"orders-api\"]", audit.events.single().details["resources"])
    }

    @Test
    fun `sendOtp find-or-create when signup enabled creates a passwordless user`() {
        val result = newService().sendOtp(tenant.slug, "newcomer@acme.test", clientApp.clientId)

        assertTrue(result is OtpSendResult.Success)
        val created = users.findByEmail(tenant.id, "newcomer@acme.test")!!
        assertEquals(User.SENTINEL_PASSWORD_HASH, created.passwordHash)
        assertFalse(created.emailVerified)
        assertEquals(1, email.otps.size)
    }

    @Test
    fun `sendOtp returns uniform success without creating user when signup disabled`() {
        val locked =
            tenants.update(tenant.copy(securityConfig = tenant.securityConfig.copy(emailOtpSignupEnabled = false)))
        val result = newService().sendOtp(locked.slug, "ghost@acme.test", clientApp.clientId)

        assertTrue(result is OtpSendResult.Success)
        assertNull(users.findByEmail(locked.id, "ghost@acme.test"))
        assertEquals(0, challenges.all().size)
        assertEquals(0, email.otps.size)
        assertTrue(audit.events.isEmpty(), "no audit event when nothing actually happened")
    }

    @Test
    fun `sendOtp resend invalidates the prior challenge for the same user`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )
        val service = newService()
        val first = service.sendOtp(tenant.slug, "alice@acme.test") as OtpSendResult.Success
        val second = service.sendOtp(tenant.slug, "alice@acme.test") as OtpSendResult.Success

        assertNotEquals(first.challengeId, second.challengeId)
        assertNull(challenges.findByChallengeId(first.challengeId))
        val active = challenges.findByChallengeId(second.challengeId)!!
        assertEquals(1, active.resendCount)
    }

    @Test
    fun `sendOtp swallows SMTP failure but still records the audit event`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )
        email.shouldFail = true

        val result = newService().sendOtp(tenant.slug, "alice@acme.test")

        assertTrue(result is OtpSendResult.Success)
        assertEquals(AuditEventType.EMAIL_OTP_SENT, audit.events.single().eventType)
    }

    @Test
    fun `verifyOtp on the stale handle after resend returns InvalidOtp`() {
        users.add(
            User(
                tenantId = tenant.id,
                username = "alice",
                email = "alice@acme.test",
                fullName = "Alice",
                passwordHash = "x",
            ),
        )
        val service = newService()
        val first = service.sendOtp(tenant.slug, "alice@acme.test") as OtpSendResult.Success
        service.sendOtp(tenant.slug, "alice@acme.test")

        val result = service.verifyOtp(tenant.slug, first.challengeId, "123456")

        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.InvalidOtp, result.error)
    }

    @Test
    fun `sendOtp returns TenantNotFound for unknown slug`() {
        val result = newService().sendOtp("nope", "x@y.test")
        assertTrue(result is OtpSendResult.Failure)
        assertEquals(OtpError.TenantNotFound, result.error)
    }

    // ----- verifyOtp -----

    private fun seedChallenge(
        codeRaw: String = "123456",
        clientId: String? = null,
        resources: List<String> = emptyList(),
    ): Pair<User, com.kauth.domain.model.EmailOtpChallenge> {
        val user =
            users.add(
                User(
                    tenantId = tenant.id,
                    username = "alice",
                    email = "alice@acme.test",
                    fullName = "Alice",
                    passwordHash = "x",
                ),
            )
        val ch =
            challenges.create(
                com.kauth.domain.model.EmailOtpChallenge(
                    userId = user.id!!,
                    tenantId = tenant.id,
                    challengeId = "handle-1",
                    codeHash = sha256Hex(codeRaw),
                    originatingClientId = clientId,
                    resources = resources,
                    expiresAt = fixedClock.instant().plusSeconds(600),
                ),
            )
        return user to ch
    }

    @Test
    fun `verifyOtp issues an auth code, stamps email_verified, and consumes the challenge`() {
        seedChallenge(clientId = clientApp.clientId)
        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")

        assertTrue(result is OtpVerifyResult.Success)
        val stored = authCodes.findByCode(result.authorizationCode)!!
        assertEquals(clientApp.id, stored.clientId)
        assertEquals(clientApp.redirectUris.first(), stored.redirectUri)
        val consumed = challenges.findByChallengeId("handle-1")!!
        assertNotNull(consumed.consumedAt)
        assertTrue(users.findByEmail(tenant.id, "alice@acme.test")!!.emailVerified)
        assertEquals(AuditEventType.EMAIL_OTP_VERIFIED, audit.events.last().eventType)
    }

    @Test
    fun `verifyOtp copies authorized OTP resources to the issued auth code`() {
        val resource =
            resources.seed(
                ResourceServer(
                    id = null,
                    tenantId = tenant.id,
                    identifier = "orders-api",
                    name = "Orders API",
                    description = null,
                ),
            )
        resources.setAuthorizedResources(clientApp.id, listOf(resource.id!!))
        seedChallenge(clientId = clientApp.clientId, resources = listOf("orders-api"))

        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")

        assertTrue(result is OtpVerifyResult.Success)
        val stored = authCodes.findByCode(result.authorizationCode)!!
        assertEquals(listOf("orders-api"), stored.resources)
    }

    @Test
    fun `verifyOtp rejects unauthorized OTP resources`() {
        seedChallenge(clientId = clientApp.clientId, resources = listOf("orders-api"))

        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")

        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.InvalidClient, result.error)
        assertTrue(authCodes.all().isEmpty())
    }

    @Test
    fun `verifyOtp grants client default roles on success`() {
        seedChallenge(clientId = clientApp.clientId)
        val viewerRole =
            roles.add(Role(id = null, tenantId = tenant.id, name = "viewer"))
        roles.setDefaultRolesForClient(clientApp.id, listOf(viewerRole.id!!))

        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")
        assertTrue(result is OtpVerifyResult.Success)
        val user = users.findByEmail(tenant.id, "alice@acme.test")!!
        assertTrue(roles.findRolesForUser(user.id!!).any { it.id == viewerRole.id })
    }

    @Test
    fun `verifyOtp wrong code increments attempts and returns invalid_otp`() {
        seedChallenge()
        val result = newService().verifyOtp(tenant.slug, "handle-1", "999999")

        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.InvalidOtp, result.error)
        assertEquals(1, challenges.findByChallengeId("handle-1")!!.attemptCount)
    }

    @Test
    fun `verifyOtp returns too_many_attempts once attempts reach the cap`() {
        val (_, ch) = seedChallenge(clientId = clientApp.clientId)
        repeat(EmailOtpService.MAX_ATTEMPTS_PER_CHALLENGE - 1) { challenges.incrementAttempts(ch.id!!) }
        // 4 attempts already recorded; the fifth wrong code crosses the cap.
        val service = newService()
        val result = service.verifyOtp(tenant.slug, "handle-1", "999999")
        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.TooManyAttempts, result.error)
    }

    @Test
    fun `verifyOtp returns ChallengeExpired when past TTL`() {
        val expiredClock = Clock.fixed(Instant.parse("2026-05-25T13:00:00Z"), ZoneOffset.UTC)
        seedChallenge()
        val late =
            EmailOtpService(
                tenantRepository = tenants,
                userRepository = users,
                challengeRepository = challenges,
                applicationRepository = apps,
                authorizationCodeRepository = authCodes,
                emailPort = email,
                auditLog = audit,
                resourceServerRepository = resources,
                roleRepository = roles,
                clock = expiredClock,
            )

        val result = late.verifyOtp(tenant.slug, "handle-1", "123456")
        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.ChallengeExpired, result.error)
    }

    @Test
    fun `verifyOtp returns invalid_client when originating client has no redirect URI`() {
        val app2 =
            apps.add(
                Application(
                    id = ApplicationId(0),
                    tenantId = tenant.id,
                    clientId = "no-redirect-client",
                    name = "Bare",
                    description = null,
                    accessType = AccessType.CONFIDENTIAL,
                    enabled = true,
                    redirectUris = emptyList(),
                ),
            )
        seedChallenge(clientId = app2.clientId)

        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")
        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.InvalidClient, result.error)
    }

    // ----- defensive lockout -----

    @Test
    fun `cross-challenge failures trip account lockout at the configured threshold`() {
        val service = newService()
        val (user, _) = seedChallenge()
        // Burn through the per-challenge cap to fire the cross-challenge counter once.
        repeat(EmailOtpService.MAX_ATTEMPTS_PER_CHALLENGE) {
            service.verifyOtp(tenant.slug, "handle-1", "999999")
        }
        // Counter is now 1 after the first cycle; reissue for the same user and burn again.
        val threshold = tenant.securityConfig.emailOtpLockoutThreshold
        repeat(threshold - 1) { round ->
            challenges.clear()
            challenges.create(
                com.kauth.domain.model.EmailOtpChallenge(
                    userId = user.id!!,
                    tenantId = tenant.id,
                    challengeId = "handle-extra-$round",
                    codeHash = sha256Hex("123456"),
                    expiresAt = fixedClock.instant().plusSeconds(600),
                ),
            )
            repeat(EmailOtpService.MAX_ATTEMPTS_PER_CHALLENGE) {
                service.verifyOtp(tenant.slug, "handle-extra-$round", "999999")
            }
        }

        val locked = users.findById(user.id!!, tenant.id)!!
        assertNotNull(locked.lockedUntil, "lockout timestamp should be set after $threshold cycles")
        assertEquals(0, locked.failedOtpChallenges, "counter resets when lockout fires")
        assertTrue(audit.events.any { it.eventType == AuditEventType.EMAIL_OTP_LOCKOUT })
    }

    @Test
    fun `locked user is rejected with too_many_attempts and never leaks lock status`() {
        val (user, _) = seedChallenge()
        users.recordFailedOtpChallenge(
            user.id!!,
            0,
            lockedUntil = fixedClock.instant().plusSeconds(600),
        )
        val result = newService().verifyOtp(tenant.slug, "handle-1", "123456")

        assertTrue(result is OtpVerifyResult.Failure)
        assertEquals(OtpError.TooManyAttempts, result.error)
    }
}
