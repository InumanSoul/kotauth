package com.kauth.domain.service

import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.InMemoryRateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsernameChangeServiceTest {
    private val users = FakeUserRepository()
    private val tenants = FakeTenantRepository()
    private val audit = FakeAuditLogPort()
    private val tenantId = TenantId(1)
    private val alice = UserId(10)
    private val now = Instant.parse("2026-11-25T12:00:00Z")

    private fun service(
        at: Instant = now,
        limiter: InMemoryRateLimiter = InMemoryRateLimiter(maxRequests = 10, windowSeconds = 3600),
    ) = UsernameChangeService(users, tenants, audit, limiter, clock = Clock.fixed(at, ZoneOffset.UTC))

    @BeforeTest
    fun setUp() {
        users.clear()
        tenants.clear()
        audit.clear()
        workspace(enabled = true)
        users.add(user(alice, "alice", "alice@acme.test"))
        users.add(user(UserId(11), "bob", "bob@acme.test"))
    }

    @Test
    fun `a user renames themselves, and the audit log says it was them`() {
        val result = service().changeOwn(tenantId, alice, "  Alice.K ", ipAddress = "203.0.113.4")

        val changed = assertIs<UsernameChangeResult.Changed>(result)
        assertEquals("alice.k", changed.user.username)
        assertEquals(now, users.findById(alice, tenantId)?.usernameSelfChangedAt)
        val event = audit.events.single()
        assertEquals(AuditEventType.USER_USERNAME_CHANGED, event.eventType)
        assertEquals(mapOf("username" to "alice", "newUsername" to "alice.k"), event.details)
        assertEquals("203.0.113.4", event.ipAddress)
    }

    @Test
    fun `nothing changes in a workspace that keeps usernames to administrators`() {
        workspace(enabled = false)

        assertEquals(UsernameChangeResult.Disabled, service().changeOwn(tenantId, alice, "alice.k", null))
        assertEquals("alice", users.findById(alice, tenantId)?.username)
    }

    @Test
    fun `a second rename waits out the cooldown`() {
        service().changeOwn(tenantId, alice, "alice.k", null)

        val soon = service(at = now.plus(Duration.ofDays(29))).changeOwn(tenantId, alice, "alice.j", null)
        assertEquals(UsernameChangeResult.CoolingDown(now.plus(UsernameChangeService.COOLDOWN)), soon)

        val later = service(at = now.plus(Duration.ofDays(31))).changeOwn(tenantId, alice, "alice.j", null)
        assertIs<UsernameChangeResult.Changed>(later)
    }

    @Test
    fun `an administrator rename starts no cooldown`() {
        users.update(users.findById(alice, tenantId)!!.copy(username = "renamed.by.admin"))

        val user = users.findById(alice, tenantId)!!
        assertNull(user.usernameSelfChangedAt)
        assertNull(service().nextAllowedAt(user))
    }

    @Test
    fun `the admin rules apply to format, another username and another user's email`() {
        val s = service()
        assertIs<UsernameChangeResult.Invalid>(s.changeOwn(tenantId, alice, "alice k", null))
        assertIs<UsernameChangeResult.Taken>(s.changeOwn(tenantId, alice, "bob", null))
        assertIs<UsernameChangeResult.Invalid>(s.changeOwn(tenantId, alice, "bob@acme.test", null))
        assertEquals(UsernameChangeResult.Unchanged, s.changeOwn(tenantId, alice, "ALICE", null))
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun `every attempt counts, so the uniqueness check can't be used to list usernames`() {
        val s = service(limiter = InMemoryRateLimiter(maxRequests = 3, windowSeconds = 3600))
        repeat(3) { assertIs<UsernameChangeResult.Taken>(s.changeOwn(tenantId, alice, "bob", null)) }

        assertEquals(UsernameChangeResult.TooManyAttempts, s.changeOwn(tenantId, alice, "free.name", null))
    }

    private fun workspace(enabled: Boolean) {
        tenants.add(
            Tenant(
                id = tenantId,
                slug = "acme",
                displayName = "Acme",
                issuerUrl = null,
                securityConfig = SecurityConfig(usernameChangeEnabled = enabled),
            ),
        )
    }

    private fun user(
        id: UserId,
        username: String,
        email: String,
    ) = User(
        id = id,
        tenantId = tenantId,
        username = username,
        email = email,
        fullName = username,
        passwordHash = "hashed:pw",
        enabled = true,
    )
}
