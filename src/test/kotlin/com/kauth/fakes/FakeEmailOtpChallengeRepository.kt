package com.kauth.fakes

import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.UserId
import com.kauth.domain.port.EmailOtpChallengeRepository
import java.time.Instant

class FakeEmailOtpChallengeRepository : EmailOtpChallengeRepository {
    private val store = mutableMapOf<Int, EmailOtpChallenge>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<EmailOtpChallenge> = store.values.toList()

    override fun create(challenge: EmailOtpChallenge): EmailOtpChallenge {
        val saved = challenge.copy(id = nextId++)
        store[saved.id!!] = saved
        return saved
    }

    override fun findByChallengeId(challengeId: String): EmailOtpChallenge? =
        store.values.find { it.challengeId == challengeId }

    override fun incrementAttempts(id: Int): Int {
        val existing = store[id] ?: return 0
        val updated = existing.copy(attemptCount = existing.attemptCount + 1)
        store[id] = updated
        return updated.attemptCount
    }

    override fun markConsumed(
        id: Int,
        consumedAt: Instant,
    ) {
        store[id]?.let { store[id] = it.copy(consumedAt = consumedAt) }
    }

    override fun deleteActiveByUser(userId: UserId): Int {
        val matches = store.values.filter { it.userId == userId && it.consumedAt == null }
        matches.forEach { store.remove(it.id) }
        return matches.size
    }

    override fun deleteExpired(now: Instant): Int {
        val expired = store.values.filter { now.isAfter(it.expiresAt) }
        expired.forEach { store.remove(it.id) }
        return expired.size
    }
}
