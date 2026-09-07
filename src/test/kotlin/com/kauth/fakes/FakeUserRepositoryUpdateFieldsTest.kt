package com.kauth.fakes

import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the class of bug where `PostgresUserRepository.update()` silently
 * dropped a mutable [User] field — the username column was in the `UPDATE`'s `WHERE` clause but
 * never its `SET` list, so an admin rename returned success while the row never changed.
 *
 * This enumerates every field `PostgresUserRepository.update()` is expected to persist, mutates
 * it, calls `update()`, and re-reads. Against [FakeUserRepository] every one of these passes
 * trivially — the fake stores the whole object on `update()` — so this test cannot catch a
 * Postgres-only regression by itself. Its value is as a portable contract: the same assertions,
 * pointed at a Postgres-backed repository in an integration suite, would have failed exactly the
 * way the username bug should have been caught. Keep this list in sync with
 * `PostgresUserRepository.update()`'s `SET` list when either changes.
 *
 * Deliberately excluded (see the harden-2 report for the full audit): `id`/`tenantId`/`createdAt`
 * (immutable), `passwordHash`/`lastPasswordChangeAt` (written together by `updatePassword`), and
 * `failedLoginAttempts`/`lockedUntil`/`failedOtpChallenges` (written by the dedicated
 * lockout-tracking methods).
 */
class FakeUserRepositoryUpdateFieldsTest {
    private val tenantId = TenantId(1)

    private fun baseUser() =
        User(
            tenantId = tenantId,
            username = "original",
            email = "original@example.com",
            fullName = "Original Name",
            passwordHash = "hash",
        )

    private fun savedUser(repo: FakeUserRepository): User = repo.save(baseUser())

    @Test
    fun `update persists a changed username`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(username = "renamed"))
        assertEquals("renamed", repo.findById(saved.id!!, tenantId)!!.username)
    }

    @Test
    fun `update persists a changed email`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(email = "new@example.com"))
        assertEquals("new@example.com", repo.findById(saved.id!!, tenantId)!!.email)
    }

    @Test
    fun `update persists a changed fullName`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(fullName = "New Name"))
        assertEquals("New Name", repo.findById(saved.id!!, tenantId)!!.fullName)
    }

    @Test
    fun `update persists a changed externalId`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(externalId = "ext-42"))
        assertEquals("ext-42", repo.findById(saved.id!!, tenantId)!!.externalId)
    }

    @Test
    fun `update persists a changed givenName`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(givenName = "Ada"))
        assertEquals("Ada", repo.findById(saved.id!!, tenantId)!!.givenName)
    }

    @Test
    fun `update persists a changed familyName`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(familyName = "Lovelace"))
        assertEquals("Lovelace", repo.findById(saved.id!!, tenantId)!!.familyName)
    }

    @Test
    fun `update persists a changed emailVerified flag`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(emailVerified = true))
        assertEquals(true, repo.findById(saved.id!!, tenantId)!!.emailVerified)
    }

    @Test
    fun `update persists a changed enabled flag`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(enabled = false))
        assertEquals(false, repo.findById(saved.id!!, tenantId)!!.enabled)
    }

    @Test
    fun `update persists a changed mfaEnabled flag`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(mfaEnabled = true))
        assertEquals(true, repo.findById(saved.id!!, tenantId)!!.mfaEnabled)
    }

    @Test
    fun `update persists changed requiredActions`() {
        val repo = FakeUserRepository()
        val saved = savedUser(repo)
        repo.update(saved.copy(requiredActions = setOf(RequiredAction.CHANGE_PASSWORD)))
        assertEquals(
            setOf(RequiredAction.CHANGE_PASSWORD),
            repo.findById(saved.id!!, tenantId)!!.requiredActions,
        )
    }
}
