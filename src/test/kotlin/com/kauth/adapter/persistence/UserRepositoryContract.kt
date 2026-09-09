package com.kauth.adapter.persistence

import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.port.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behaviour every [UserRepository] must exhibit, run against each implementation.
 *
 * This exists because of a bug that shipped in v1.24.0. `PostgresUserRepository.update` never
 * wrote the `username` column, so admin username renames silently did nothing against a real
 * database — the API returned success, the audit event was written, and the row was unchanged.
 * Every test passed, because `FakeUserRepository` stores the whole `User` object and no test
 * compared the two implementations.
 *
 * The guard is the shape of these tests rather than any single assertion: **mutate a field, then
 * re-read through the repository and assert on what came back.** A test that asserts on the value
 * returned by `update()` proves nothing, because an adapter is free to return its own input — and
 * `PostgresGroupRepository.update` still does exactly that.
 *
 * To cover another port, subclass this pattern rather than copying assertions: one abstract
 * fixture method, two concrete subclasses, one of them tagged `postgres`.
 */
abstract class UserRepositoryContract {
    /** The implementation under test. */
    protected abstract val repo: UserRepository

    /** A tenant that exists in this implementation's world. */
    protected abstract fun tenantId(): TenantId

    private var seq = 0

    private fun newUser(tenantId: TenantId): User {
        seq++
        return User(
            tenantId = tenantId,
            username = "contract.user$seq",
            email = "contract.user$seq@example.com",
            fullName = "Contract User $seq",
            passwordHash = "hash-$seq",
        )
    }

    @Test
    fun `save then findById returns every field that was written`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val id = assertNotNull(saved.id, "save must return a persisted id")

        val found = assertNotNull(repo.findById(id, tid), "the saved user must be findable")
        assertEquals(saved.username, found.username)
        assertEquals(saved.email, found.email)
        assertEquals(saved.fullName, found.fullName)
        assertEquals(tid, found.tenantId)
    }

    @Test
    fun `update persists the username — re-read, never the returned object`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val id = assertNotNull(saved.id)

        repo.update(saved.copy(username = "contract.renamed$seq"))

        // Deliberately ignoring update()'s return value: an adapter may return its input.
        val reread = assertNotNull(repo.findById(id, tid))
        assertEquals(
            "contract.renamed$seq",
            reread.username,
            "username must survive a round-trip through the database, not just the returned object",
        )
    }

    @Test
    fun `update persists every mutable profile field`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val id = assertNotNull(saved.id)

        val mutated =
            saved.copy(
                username = "contract.mutated$seq",
                email = "contract.mutated$seq@example.com",
                fullName = "Mutated Name",
                givenName = "Given",
                familyName = "Family",
                externalId = "ext-$seq",
                emailVerified = !saved.emailVerified,
                enabled = !saved.enabled,
            )
        repo.update(mutated)

        val reread = assertNotNull(repo.findById(id, tid))
        assertEquals(mutated.username, reread.username, "username")
        assertEquals(mutated.email, reread.email, "email")
        assertEquals(mutated.fullName, reread.fullName, "fullName")
        assertEquals(mutated.givenName, reread.givenName, "givenName")
        assertEquals(mutated.familyName, reread.familyName, "familyName")
        assertEquals(mutated.externalId, reread.externalId, "externalId")
        assertEquals(mutated.emailVerified, reread.emailVerified, "emailVerified")
        assertEquals(mutated.enabled, reread.enabled, "enabled")
    }

    @Test
    fun `update persists requiredActions`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val id = assertNotNull(saved.id)

        repo.update(saved.copy(requiredActions = setOf(RequiredAction.CHANGE_PASSWORD)))

        val reread = assertNotNull(repo.findById(id, tid))
        assertEquals(setOf(RequiredAction.CHANGE_PASSWORD), reread.requiredActions)
    }

    @Test
    fun `findByUsername locates a renamed user by its new name, not its old one`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val old = saved.username
        repo.update(saved.copy(username = "contract.after$seq"))

        assertNotNull(
            repo.findByUsername(tid, "contract.after$seq"),
            "a rename must be visible to the lookup the sign-in path uses",
        )
        assertEquals(null, repo.findByUsername(tid, old), "the old username must no longer resolve")
    }

    @Test
    fun `email is stored lowercased`() {
        val tid = tenantId()
        seq++
        val saved =
            repo.save(
                User(
                    tenantId = tid,
                    username = "contract.case$seq",
                    email = "Contract.Case$seq@Example.COM",
                    fullName = "Case Test",
                    passwordHash = "hash",
                ),
            )
        val reread = assertNotNull(repo.findById(assertNotNull(saved.id), tid))
        assertEquals(reread.email, reread.email.lowercase(), "stored email must be lowercase")
        assertNotNull(
            repo.findByEmail(tid, "contract.case$seq@example.com"),
            "lookup must find it by the lowercased address",
        )
    }

    @Test
    fun `findById does not cross tenants`() {
        val tid = tenantId()
        val saved = repo.save(newUser(tid))
        val id = assertNotNull(saved.id)
        val otherTenant = TenantId(tid.value + 9_999)

        assertTrue(
            repo.findById(id, otherTenant) == null,
            "a user must not be readable through another tenant's id",
        )
    }
}
