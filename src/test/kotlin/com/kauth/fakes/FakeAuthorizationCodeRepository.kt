package com.kauth.fakes

import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.port.AuthorizationCodeRepository
import java.time.Instant

/**
 * In-memory AuthorizationCodeRepository for unit tests.
 * Codes are looked up by their string value (the random code, not the PK).
 */
class FakeAuthorizationCodeRepository : AuthorizationCodeRepository {
    private val store = mutableMapOf<String, AuthorizationCode>()
    private var nextId = 1

    fun clear() {
        store.clear()
        nextId = 1
    }

    fun all() = store.values.toList()

    override fun save(code: AuthorizationCode): AuthorizationCode {
        val saved = code.copy(id = nextId++)
        store[saved.code] = saved
        return saved
    }

    override fun findByCode(code: String) = store[code]

    override fun markUsed(
        code: String,
        usedAt: Instant,
    ) {
        store[code]?.let { store[code] = it.copy(usedAt = usedAt) }
    }
}
