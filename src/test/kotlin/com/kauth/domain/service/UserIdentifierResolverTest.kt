package com.kauth.domain.service

import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UserIdentifierResolverTest {
    private val users = FakeUserRepository()
    private val resolver = UserIdentifierResolver(users)
    private val tenantId = TenantId(1)

    private fun user(
        id: Int,
        username: String,
        email: String,
        forTenant: TenantId = tenantId,
    ) = User(
        id = UserId(id),
        tenantId = forTenant,
        username = username,
        email = email,
        fullName = "Test User",
        passwordHash = "hash",
    )

    @BeforeTest
    fun setup() {
        users.clear()
        users.add(user(1, "alice", "alice@example.com"))
    }

    @Test
    fun `USERNAME mode matches username`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.USERNAME, "alice")
        assertIs<IdentifierResolution.Found>(result)
        assertEquals("alice", result.user.username)
    }

    @Test
    fun `USERNAME mode does not match email`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.USERNAME, "alice@example.com")
        assertIs<IdentifierResolution.NotFound>(result)
    }

    @Test
    fun `EMAIL mode matches email case-insensitively`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EMAIL, "ALICE@Example.com")
        assertIs<IdentifierResolution.Found>(result)
        assertEquals("alice", result.user.username)
    }

    @Test
    fun `EMAIL mode does not match username`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EMAIL, "alice")
        assertIs<IdentifierResolution.NotFound>(result)
    }

    @Test
    fun `EITHER mode matches username`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EITHER, "alice")
        assertIs<IdentifierResolution.Found>(result)
    }

    @Test
    fun `EITHER mode matches email`() {
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EITHER, "alice@example.com")
        assertIs<IdentifierResolution.Found>(result)
    }

    @Test
    fun `EITHER mode refuses when username and email match different users`() {
        users.clear()
        // User 1's username is the same string as user 2's email address.
        users.add(user(1, "shared@example.com", "one@example.com"))
        users.add(user(2, "two", "shared@example.com"))
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EITHER, "shared@example.com")
        assertIs<IdentifierResolution.Ambiguous>(result)
    }

    @Test
    fun `EITHER mode is not ambiguous when both hits are the same user`() {
        users.clear()
        users.add(user(1, "self@example.com", "self@example.com"))
        val result = resolver.resolve(tenantId, LoginIdentifierMode.EITHER, "self@example.com")
        assertIs<IdentifierResolution.Found>(result)
        assertEquals(UserId(1), result.user.id)
    }

    @Test
    fun `blank input never resolves the legacy empty-email row`() {
        users.clear()
        users.add(user(1, "legacy", ""))
        for (mode in LoginIdentifierMode.entries) {
            assertIs<IdentifierResolution.NotFound>(resolver.resolve(tenantId, mode, ""))
            assertIs<IdentifierResolution.NotFound>(resolver.resolve(tenantId, mode, "   "))
        }
    }

    @Test
    fun `EITHER mode issues both lookups even when username hits`() {
        users.callLog.clear()
        resolver.resolve(tenantId, LoginIdentifierMode.EITHER, "alice")
        assertEquals(
            listOf("findByUsername", "findByEmail"),
            users.callLog,
            "EITHER must not short-circuit — a hit and a miss must cost the same lookups",
        )
    }

    @Test
    fun `does not cross tenant boundaries`() {
        // A same-named user genuinely exists — under a different tenant. If the lookup weren't
        // actually tenant-scoped (e.g. it silently ignored tenantId), this would incorrectly
        // resolve. A tenant with no users at all wouldn't distinguish "scoped correctly" from
        // "scoping is broken but there was nothing to find anyway".
        users.add(user(2, "alice", "alice2@example.com", forTenant = TenantId(2)))

        val result = resolver.resolve(TenantId(99), LoginIdentifierMode.EITHER, "alice")
        assertIs<IdentifierResolution.NotFound>(result)
    }
}
