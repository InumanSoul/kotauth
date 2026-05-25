package com.kauth.fakes

import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeEmailOtpChallengeRepositoryTest {
    private val repo = FakeEmailOtpChallengeRepository()
    private val tenantId = TenantId(1)
    private val userId = UserId(42)

    @BeforeTest
    fun reset() = repo.clear()

    private fun newChallenge(
        challengeId: String = "ch-1",
        expiresAt: Instant = Instant.now().plusSeconds(600),
        codeHash: String = "hash-1",
    ) = EmailOtpChallenge(
        userId = userId,
        tenantId = tenantId,
        challengeId = challengeId,
        codeHash = codeHash,
        expiresAt = expiresAt,
    )

    @Test
    fun `create assigns id and persists`() {
        val saved = repo.create(newChallenge())
        assertNotNull(saved.id)
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `findByChallengeId returns the matching row`() {
        val saved = repo.create(newChallenge("opaque-handle"))
        val found = repo.findByChallengeId("opaque-handle")
        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `findByChallengeId returns null for unknown handle`() {
        repo.create(newChallenge("a"))
        assertNull(repo.findByChallengeId("b"))
    }

    @Test
    fun `incrementAttempts returns the new count`() {
        val saved = repo.create(newChallenge())
        val first = repo.incrementAttempts(saved.id!!)
        val second = repo.incrementAttempts(saved.id)
        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun `markConsumed sets the timestamp and flips isValid off`() {
        val saved = repo.create(newChallenge())
        val now = Instant.now()
        repo.markConsumed(saved.id!!, now)
        val reloaded = repo.findByChallengeId(saved.challengeId)!!
        assertEquals(now, reloaded.consumedAt)
        assertTrue(reloaded.isConsumed)
        assertTrue(!reloaded.isValid)
    }

    @Test
    fun `deleteActiveByUser removes only unconsumed rows for that user`() {
        val active = repo.create(newChallenge("a"))
        val consumed = repo.create(newChallenge("b"))
        repo.markConsumed(consumed.id!!)
        repo.create(newChallenge("c").copy(userId = UserId(99)))

        val deleted = repo.deleteActiveByUser(userId)
        assertEquals(1, deleted)
        assertNull(repo.findByChallengeId("a"))
        assertNotNull(repo.findByChallengeId("b"))
        assertNotNull(repo.findByChallengeId("c"))
        // The previously created `active` reference is the row that got removed.
        assertNull(repo.findByChallengeId(active.challengeId))
    }

    @Test
    fun `deleteExpired removes only rows past expiry`() {
        repo.create(newChallenge("future", expiresAt = Instant.now().plusSeconds(60)))
        repo.create(newChallenge("past", expiresAt = Instant.now().minusSeconds(60)))

        val deleted = repo.deleteExpired()
        assertEquals(1, deleted)
        assertNotNull(repo.findByChallengeId("future"))
        assertNull(repo.findByChallengeId("past"))
    }
}
