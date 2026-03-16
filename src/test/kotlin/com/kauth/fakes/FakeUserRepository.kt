package com.kauth.fakes

import com.kauth.domain.model.User
import com.kauth.domain.port.UserRepository
import java.time.Instant

/**
 * In-memory UserRepository for unit tests.
 * Users are stored in a flat map keyed by id. All lookups are tenant-scoped.
 */
class FakeUserRepository : UserRepository {

    private val store = mutableMapOf<Int, User>()
    private var nextId = 1

    fun add(user: User): User {
        val u = if (user.id == null) user.copy(id = nextId++) else user
        store[u.id!!] = u
        return u
    }

    fun clear() { store.clear(); nextId = 1 }

    override fun findById(id: Int) = store[id]

    override fun findByUsername(tenantId: Int, username: String) =
        store.values.find { it.tenantId == tenantId && it.username == username }

    override fun findByEmail(tenantId: Int, email: String) =
        store.values.find { it.tenantId == tenantId && it.email == email }

    override fun findByTenantId(tenantId: Int, search: String?): List<User> {
        val all = store.values.filter { it.tenantId == tenantId }
        if (search.isNullOrBlank()) return all
        val q = search.lowercase()
        return all.filter {
            it.username.lowercase().contains(q) ||
            it.email.lowercase().contains(q) ||
            it.fullName.lowercase().contains(q)
        }
    }

    override fun save(user: User): User {
        val u = if (user.id == null) user.copy(id = nextId++) else user
        store[u.id!!] = u
        return u
    }

    override fun update(user: User): User {
        store[user.id!!] = user
        return user
    }

    override fun updatePassword(userId: Int, passwordHash: String, changedAt: Instant): User {
        val updated = store[userId]!!.copy(passwordHash = passwordHash, lastPasswordChangeAt = changedAt)
        store[userId] = updated
        return updated
    }

    override fun existsByUsername(tenantId: Int, username: String) =
        store.values.any { it.tenantId == tenantId && it.username == username }

    override fun existsByEmail(tenantId: Int, email: String) =
        store.values.any { it.tenantId == tenantId && it.email == email }
}
