package com.kauth.adapter.persistence

import com.kauth.domain.model.RequiredAction
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persistence adapter — implements the UserRepository port using PostgreSQL + Exposed.
 *
 * All queries are scoped by tenantId. There is no API surface here that
 * allows cross-tenant access — the constraint is structural, not convention.
 */
class PostgresUserRepository : UserRepository {
    override fun findById(
        id: UserId,
        tenantId: TenantId,
    ): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { (UsersTable.id eq id.value) and (UsersTable.tenantId eq tenantId.value) }
                .map { it.toUser() }
                .singleOrNull()
        }

    override fun findByUsername(
        tenantId: TenantId,
        username: String,
    ): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { (UsersTable.tenantId eq tenantId.value) and (UsersTable.username eq username) }
                .map { it.toUser() }
                .singleOrNull()
        }

    override fun findByEmail(
        tenantId: TenantId,
        email: String,
    ): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { (UsersTable.tenantId eq tenantId.value) and (UsersTable.email eq email.lowercase()) }
                .map { it.toUser() }
                .singleOrNull()
        }

    override fun findByTenantId(
        tenantId: TenantId,
        search: String?,
        limit: Int,
        offset: Int,
    ): List<User> =
        transaction {
            val query =
                UsersTable
                    .selectAll()
                    .where { UsersTable.tenantId eq tenantId.value }
            if (!search.isNullOrBlank()) {
                val term = "%${search.lowercase()}%"
                query.andWhere {
                    (UsersTable.username.lowerCase() like term) or
                        (UsersTable.email.lowerCase() like term) or
                        (UsersTable.fullName.lowerCase() like term)
                }
            }
            query
                .orderBy(UsersTable.id)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toUser() }
        }

    override fun countByTenantId(
        tenantId: TenantId,
        search: String?,
    ): Long =
        transaction {
            val query =
                UsersTable
                    .selectAll()
                    .where { UsersTable.tenantId eq tenantId.value }
            if (!search.isNullOrBlank()) {
                val term = "%${search.lowercase()}%"
                query.andWhere {
                    (UsersTable.username.lowerCase() like term) or
                        (UsersTable.email.lowerCase() like term) or
                        (UsersTable.fullName.lowerCase() like term)
                }
            }
            query.count()
        }

    override fun update(user: User): User =
        transaction {
            UsersTable.update({ UsersTable.id eq user.id!!.value }) {
                it[email] = user.email.lowercase()
                it[fullName] = user.fullName
                it[emailVerified] = user.emailVerified
                it[enabled] = user.enabled
                it[mfaEnabled] = user.mfaEnabled
                it[requiredActions] = user.requiredActions.map { a -> a.name }
            }
            user
        }

    override fun updatePassword(
        userId: UserId,
        passwordHash: String,
        changedAt: Instant,
    ): User =
        transaction {
            val ts = changedAt.toOffsetDateTime()
            UsersTable.update({ UsersTable.id eq userId.value }) {
                it[UsersTable.passwordHash] = passwordHash
                it[UsersTable.lastPasswordChangeAt] = ts
            }
            UsersTable
                .selectAll()
                .where { UsersTable.id eq userId.value }
                .single()
                .toUser()
        }

    override fun save(user: User): User =
        transaction {
            val insertedId =
                UsersTable.insert {
                    it[tenantId] = user.tenantId.value
                    it[username] = user.username
                    it[email] = user.email.lowercase()
                    it[passwordHash] = user.passwordHash
                    it[fullName] = user.fullName
                    it[emailVerified] = user.emailVerified
                    it[enabled] = user.enabled
                    it[requiredActions] = user.requiredActions.map { a -> a.name }
                } get UsersTable.id

            user.copy(id = UserId(insertedId))
        }

    override fun existsByUsername(
        tenantId: TenantId,
        username: String,
    ): Boolean =
        transaction {
            UsersTable
                .selectAll()
                .where { (UsersTable.tenantId eq tenantId.value) and (UsersTable.username eq username) }
                .count() > 0
        }

    override fun existsByEmail(
        tenantId: TenantId,
        email: String,
    ): Boolean =
        transaction {
            UsersTable
                .selectAll()
                .where { (UsersTable.tenantId eq tenantId.value) and (UsersTable.email eq email.lowercase()) }
                .count() > 0
        }

    override fun recordFailedLogin(
        userId: UserId,
        newCount: Int,
        lockedUntil: Instant?,
    ) = transaction {
        UsersTable.update({ UsersTable.id eq userId.value }) {
            it[failedLoginAttempts] = newCount
            it[UsersTable.lockedUntil] = lockedUntil?.let { ts -> OffsetDateTime.ofInstant(ts, ZoneOffset.UTC) }
        }
        Unit
    }

    override fun resetFailedLogins(userId: UserId) =
        transaction {
            UsersTable.update({ UsersTable.id eq userId.value }) {
                it[failedLoginAttempts] = 0
                it[lockedUntil] = null
            }
            Unit
        }

    private fun ResultRow.toUser(): User =
        User(
            id = UserId(this[UsersTable.id]),
            tenantId = TenantId(this[UsersTable.tenantId]),
            username = this[UsersTable.username],
            email = this[UsersTable.email],
            passwordHash = this[UsersTable.passwordHash],
            fullName = this[UsersTable.fullName],
            emailVerified = this[UsersTable.emailVerified],
            enabled = this[UsersTable.enabled],
            requiredActions =
                this[UsersTable.requiredActions]
                    .mapNotNull { name -> runCatching { RequiredAction.valueOf(name) }.getOrNull() }
                    .toSet(),
            lastPasswordChangeAt = this[UsersTable.lastPasswordChangeAt]?.toInstant(),
            mfaEnabled = this[UsersTable.mfaEnabled],
            failedLoginAttempts = this[UsersTable.failedLoginAttempts],
            lockedUntil = this[UsersTable.lockedUntil]?.toInstant(),
            createdAt = this[UsersTable.createdAt].toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
