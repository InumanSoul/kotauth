package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserRepository
import com.kauth.fakes.FakeUserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UsernameGeneratorTest {
    private val users = FakeUserRepository()
    private val generator = UsernameGenerator(users)
    private val tenantId = TenantId(1)

    private val allowed = Regex("[a-zA-Z0-9._@+-]+")

    @BeforeTest
    fun setup() = users.clear()

    @Test
    fun `derives the stem from givenName`() {
        val name = generator.generate(tenantId, "Ana", "ana@company-a.com")
        assertTrue(name.startsWith("ana"), "got $name")
        assertTrue(name.matches(allowed), "got $name")
    }

    @Test
    fun `falls back to the email local part when givenName is null`() {
        val name = generator.generate(tenantId, null, "ana@company-a.com")
        assertTrue(name.startsWith("ana"), "got $name")
    }

    @Test
    fun `falls back to a neutral stem when both are unusable`() {
        val name = generator.generate(tenantId, "", "@@@@")
        assertTrue(name.startsWith("user"), "got $name")
        assertTrue(name.matches(allowed), "got $name")
    }

    @Test
    fun `strips characters the username validator rejects`() {
        val name = generator.generate(tenantId, "Ana Sofía!", "ana@company-a.com")
        assertTrue(name.matches(allowed), "got $name")
    }

    @Test
    fun `never exceeds the fifty character column limit`() {
        val name = generator.generate(tenantId, "a".repeat(200), "long@example.com")
        assertTrue(name.length <= 50, "got ${name.length}")
    }

    @Test
    fun `retries when the generated username already exists`() {
        val first = generator.generate(tenantId, "Ana", "ana@company-a.com")
        users.add(
            User(
                id = UserId(1),
                tenantId = tenantId,
                username = first,
                email = "someone@example.com",
                fullName = "Taken",
                passwordHash = "hash",
            ),
        )
        val second = generator.generate(tenantId, "Ana", "ana@company-a.com")
        assertNotEquals(first, second)
    }

    @Test
    fun `never generates a username that collides with an existing email`() {
        users.add(
            User(
                id = UserId(2),
                tenantId = tenantId,
                username = "someone",
                email = "ana@company-a.com",
                fullName = "Other",
                passwordHash = "hash",
            ),
        )
        repeat(20) {
            val name = generator.generate(tenantId, "Ana", "ana@company-a.com")
            assertNotEquals("ana@company-a.com", name)
        }
    }

    @Test
    fun `checks availability on the exhaustion fallback, not just the readable attempts`() {
        // Every candidate this repository is asked about is reported as taken, forcing the
        // generator through the readable-attempts loop AND the entropy-fallback loop. The
        // generator must still terminate (bounded attempts) and must consult availability in
        // both loops, not just the first — see UsernameGenerator's KDoc on the fallback.
        var lookups = 0
        val alwaysTaken =
            object : UserRepository by users {
                override fun findByUsernameIgnoreCase(
                    tenantId: TenantId,
                    username: String,
                ): User? {
                    lookups++
                    return User(
                        id = UserId(999),
                        tenantId = tenantId,
                        username = username,
                        email = "taken@example.com",
                        fullName = "Taken",
                        passwordHash = "hash",
                    )
                }

                override fun findByEmail(
                    tenantId: TenantId,
                    email: String,
                ): User? {
                    lookups++
                    return User(
                        id = UserId(999),
                        tenantId = tenantId,
                        username = "taken",
                        email = email,
                        fullName = "Taken",
                        passwordHash = "hash",
                    )
                }
            }

        val name = UsernameGenerator(alwaysTaken).generate(tenantId, "Ana", "ana@company-a.com")

        assertTrue(name.matches(allowed), "got $name")
        // isAvailable's findByUsernameIgnoreCase check short-circuits findByEmail once it finds a
        // hit, so each of the 10 readable attempts plus the 10 entropy-fallback attempts costs one
        // lookup here — 20 total confirms BOTH loops actually called isAvailable.
        assertTrue(lookups >= 20, "expected the fallback loop to also check availability, got $lookups lookups")
    }
}
