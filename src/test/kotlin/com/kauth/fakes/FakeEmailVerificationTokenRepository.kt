package com.kauth.fakes

import com.kauth.domain.model.EmailVerificationToken
import com.kauth.domain.model.UserId
import com.kauth.domain.port.EmailVerificationTokenRepository
import java.time.Instant

/**
 * In-memory EmailVerificationTokenRepository for unit tests.
 */
class FakeEmailVerificationTokenRepository : EmailVerificationTokenRepository {
    private val store = mutableMapOf<Int, EmailVerificationToken>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<EmailVerificationToken> = store.values.toList()

    override fun create(token: EmailVerificationToken): EmailVerificationToken {
        val t = token.copy(id = nextId++)
        store[t.id!!] = t
        return t
    }

    override fun findByTokenHash(hash: String): EmailVerificationToken? = store.values.find { it.tokenHash == hash }

    override fun markUsed(
        tokenId: Int,
        usedAt: Instant,
    ) {
        store[tokenId]?.let { store[tokenId] = it.copy(usedAt = usedAt) }
    }

    override fun deleteUnusedByUser(userId: UserId) {
        store.entries.removeIf { it.value.userId == userId && it.value.usedAt == null }
    }
}
