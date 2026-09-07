package com.kauth.fakes

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.UsernamePolicy
import java.time.Instant

/**
 * In-memory UserRepository for unit tests.
 * Users are stored in a flat map keyed by id. All lookups are tenant-scoped.
 */
class FakeUserRepository :
    UserRepository,
    SnapshotableFake {
    private val store = mutableMapOf<Int, User>()
    private var nextId = 1

    /**
     * [nextId] is deliberately not captured: a database sequence does not give back the ids a
     * rolled-back insert consumed, so neither does this.
     */
    override fun snapshot(): FakeRestore {
        val storeCopy = store.toMap()
        return FakeRestore {
            store.clear()
            store.putAll(storeCopy)
        }
    }

    fun add(user: User): User {
        val normalized = user.normalizedForWrite()
        requireUniquePerTenant(normalized)
        val u = if (normalized.id == null) normalized.copy(id = UserId(nextId++)) else normalized
        store[u.id!!.value] = u
        return u
    }

    /**
     * Mirrors production's write-side normalization for email only: `PostgresUserRepository.save`
     * lowercases the email column. The username is deliberately NOT silently fixed here — see
     * [requireNormalizedUsername].
     */
    private fun User.normalizedForWrite(): User {
        requireNormalizedUsername(username)
        return copy(email = email.trim().lowercase())
    }

    /**
     * Every domain write path now normalizes (trim + lowercase) a username before it ever reaches
     * a repository — see [UsernamePolicy]. Production's `PostgresUserRepository.save`/`update`
     * write the username verbatim, trusting that invariant rather than re-enforcing it. A fake
     * that silently lowercased a non-normalized username instead of rejecting it would be MORE
     * forgiving than production on exactly the invariant this release depends on: a future write
     * path that forgets to normalize would get a green test here and an unreachable user in
     * production. Throwing turns that into a failing test instead.
     */
    private fun requireNormalizedUsername(username: String) {
        val normalized = UsernamePolicy.normalize(username)
        check(username == normalized) {
            "FakeUserRepository received a non-normalized username '$username' (production would " +
                "store '$normalized'). The caller must normalize via UsernamePolicy before writing."
        }
    }

    /**
     * `UNIQUE (tenant_id, username)` and `UNIQUE (tenant_id, email)`, both from V1.
     *
     * Without them here a service that writes a colliding row gets a clean result from the fake and
     * a 500 from Postgres, and no test in a suite that never touches a database can tell the two
     * apart. The exception type is not the database's; only that this is an exception rather than a
     * value is what a domain service has to be written against.
     */
    private fun requireUniquePerTenant(user: User) {
        val clash =
            store.values.firstOrNull {
                it.tenantId == user.tenantId &&
                    it.id != user.id &&
                    (it.username == user.username || it.email == user.email)
            }
        check(clash == null) {
            "duplicate key value violates unique constraint on users (tenant_id, username|email)"
        }
    }

    /** Records lookup method names in call order — used to assert timing-invariant lookup patterns. */
    val callLog = mutableListOf<String>()

    fun clear() {
        store.clear()
        nextId = 1
        callLog.clear()
    }

    override fun findById(
        id: UserId,
        tenantId: TenantId,
    ) = store[id.value]?.takeIf { it.tenantId == tenantId }

    override fun findByIds(
        ids: Collection<UserId>,
        tenantId: TenantId,
    ) = store.values.filter { it.id in ids && it.tenantId == tenantId }

    override fun findByUsername(
        tenantId: TenantId,
        username: String,
    ): User? {
        callLog += "findByUsername"
        return store.values.find { it.tenantId == tenantId && it.username == username }
    }

    override fun findByUsernameIgnoreCase(
        tenantId: TenantId,
        username: String,
    ): User? {
        callLog += "findByUsernameIgnoreCase"
        val needle = username.trim().lowercase()
        return store.values.find { it.tenantId == tenantId && it.username.lowercase() == needle }
    }

    override fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User? {
        callLog += "findByEmail"
        return store.values.find { it.tenantId == tenantId && it.email.lowercase() == email.lowercase() }
    }

    override fun findByExternalId(
        tenantId: TenantId,
        externalId: String,
    ): User? = store.values.find { it.tenantId == tenantId && it.externalId == externalId }

    override fun findByTenantId(
        tenantId: TenantId,
        search: String?,
        limit: Int,
        offset: Int,
    ): List<User> {
        val all = store.values.filter { it.tenantId == tenantId }
        val filtered =
            if (search.isNullOrBlank()) {
                all
            } else {
                val q = search.lowercase()
                all.filter {
                    it.username.lowercase().contains(q) ||
                        it.email.lowercase().contains(q) ||
                        it.fullName.lowercase().contains(q)
                }
            }
        return filtered.drop(offset).take(limit)
    }

    override fun countByTenantId(
        tenantId: TenantId,
        search: String?,
    ): Long = findByTenantId(tenantId, search).size.toLong()

    override fun save(user: User): User {
        val normalized = user.normalizedForWrite()
        requireUniquePerTenant(normalized)
        val u = if (normalized.id == null) normalized.copy(id = UserId(nextId++)) else normalized
        store[u.id!!.value] = u
        return u
    }

    override fun update(user: User): User {
        requireNormalizedUsername(user.username)
        requireUniquePerTenant(user)
        store[user.id!!.value] = user
        return user
    }

    override fun updatePassword(
        userId: UserId,
        passwordHash: String,
        changedAt: Instant,
    ): User {
        val updated = store[userId.value]!!.copy(passwordHash = passwordHash, lastPasswordChangeAt = changedAt)
        store[userId.value] = updated
        return updated
    }

    override fun existsByUsername(
        tenantId: TenantId,
        username: String,
    ) = store.values.any { it.tenantId == tenantId && it.username == username }

    override fun existsByEmail(
        tenantId: TenantId,
        email: String,
    ) = store.values.any { it.tenantId == tenantId && it.email == email }

    override fun recordFailedLogin(
        userId: UserId,
        newCount: Int,
        lockedUntil: Instant?,
    ) {
        val user = store[userId.value] ?: return
        store[userId.value] = user.copy(failedLoginAttempts = newCount, lockedUntil = lockedUntil)
    }

    override fun resetFailedLogins(userId: UserId) {
        val user = store[userId.value] ?: return
        store[userId.value] = user.copy(failedLoginAttempts = 0, lockedUntil = null)
    }

    override fun recordFailedOtpChallenge(
        userId: UserId,
        newCount: Int,
        lockedUntil: Instant?,
    ) {
        val user = store[userId.value] ?: return
        store[userId.value] =
            user.copy(
                failedOtpChallenges = newCount,
                lockedUntil = lockedUntil ?: user.lockedUntil,
            )
    }

    override fun resetFailedOtpChallenges(userId: UserId) {
        val user = store[userId.value] ?: return
        store[userId.value] = user.copy(failedOtpChallenges = 0)
    }
}
