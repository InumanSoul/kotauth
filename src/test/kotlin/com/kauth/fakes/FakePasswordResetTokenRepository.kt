package com.kauth.fakes

import com.kauth.domain.model.PasswordResetToken
import com.kauth.domain.model.TokenPurpose
import com.kauth.domain.model.UserId
import com.kauth.domain.port.PasswordResetTokenRepository
import java.time.Instant

/**
 * In-memory PasswordResetTokenRepository for unit tests.
 */
class FakePasswordResetTokenRepository : PasswordResetTokenRepository {
    private val store = mutableMapOf<Int, PasswordResetToken>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all(): List<PasswordResetToken> = store.values.toList()

    override fun create(token: PasswordResetToken): PasswordResetToken {
        val t = token.copy(id = nextId++)
        store[t.id!!] = t
        return t
    }

    override fun findByTokenHash(hash: String): PasswordResetToken? = store.values.find { it.tokenHash == hash }

    override fun markUsed(
        tokenId: Int,
        usedAt: Instant,
    ) {
        store[tokenId]?.let { store[tokenId] = it.copy(usedAt = usedAt) }
    }

    override fun deleteByUser(userId: UserId) {
        store.entries.removeIf { it.value.userId == userId }
    }

    override fun deleteByUserAndPurpose(
        userId: UserId,
        purpose: TokenPurpose,
    ) {
        store.entries.removeIf { it.value.userId == userId && it.value.purpose == purpose }
    }
}
