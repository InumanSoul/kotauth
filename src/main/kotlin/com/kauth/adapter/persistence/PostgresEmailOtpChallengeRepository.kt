package com.kauth.adapter.persistence

import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.EmailOtpChallengeRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

object EmailOtpChallengesTable : Table("email_otp_challenges") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id") references UsersTable.id
    val tenantId = integer("tenant_id") references TenantsTable.id
    val challengeId = varchar("challenge_id", 64)
    val codeHash = varchar("code_hash", 64)
    val originatingClientId = varchar("originating_client_id", 255).nullable()
    val attemptCount = integer("attempt_count").default(0)
    val resendCount = integer("resend_count").default(0)
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

class PostgresEmailOtpChallengeRepository : EmailOtpChallengeRepository {
    override fun create(challenge: EmailOtpChallenge): EmailOtpChallenge =
        transaction {
            val insertedId =
                EmailOtpChallengesTable.insert {
                    it[userId] = challenge.userId.value
                    it[tenantId] = challenge.tenantId.value
                    it[challengeId] = challenge.challengeId
                    it[codeHash] = challenge.codeHash
                    it[originatingClientId] = challenge.originatingClientId
                    it[attemptCount] = challenge.attemptCount
                    it[resendCount] = challenge.resendCount
                    it[expiresAt] = challenge.expiresAt.toOffsetDateTime()
                    it[consumedAt] = challenge.consumedAt?.toOffsetDateTime()
                    it[createdAt] = challenge.createdAt.toOffsetDateTime()
                } get EmailOtpChallengesTable.id
            challenge.copy(id = insertedId)
        }

    override fun findByChallengeId(challengeId: String): EmailOtpChallenge? =
        transaction {
            EmailOtpChallengesTable
                .selectAll()
                .where { EmailOtpChallengesTable.challengeId eq challengeId }
                .map { it.toChallenge() }
                .singleOrNull()
        }

    override fun incrementAttempts(id: Int): Int =
        transaction {
            EmailOtpChallengesTable.update({ EmailOtpChallengesTable.id eq id }) {
                it.update(attemptCount, attemptCount + 1)
            }
            EmailOtpChallengesTable
                .selectAll()
                .where { EmailOtpChallengesTable.id eq id }
                .single()[EmailOtpChallengesTable.attemptCount]
        }

    override fun markConsumed(
        id: Int,
        consumedAt: Instant,
    ) = transaction {
        EmailOtpChallengesTable.update({ EmailOtpChallengesTable.id eq id }) {
            it[EmailOtpChallengesTable.consumedAt] = consumedAt.toOffsetDateTime()
        }
        Unit
    }

    override fun deleteActiveByUser(userId: UserId): Int =
        transaction {
            EmailOtpChallengesTable.deleteWhere {
                (EmailOtpChallengesTable.userId eq userId.value) and
                    EmailOtpChallengesTable.consumedAt.isNull()
            }
        }

    override fun deleteExpired(now: Instant): Int =
        transaction {
            EmailOtpChallengesTable.deleteWhere {
                expiresAt less now.toOffsetDateTime()
            }
        }

    private fun ResultRow.toChallenge() =
        EmailOtpChallenge(
            id = this[EmailOtpChallengesTable.id],
            userId = UserId(this[EmailOtpChallengesTable.userId]),
            tenantId = TenantId(this[EmailOtpChallengesTable.tenantId]),
            challengeId = this[EmailOtpChallengesTable.challengeId],
            codeHash = this[EmailOtpChallengesTable.codeHash],
            originatingClientId = this[EmailOtpChallengesTable.originatingClientId],
            attemptCount = this[EmailOtpChallengesTable.attemptCount],
            resendCount = this[EmailOtpChallengesTable.resendCount],
            expiresAt = this[EmailOtpChallengesTable.expiresAt].toInstant(),
            consumedAt = this[EmailOtpChallengesTable.consumedAt]?.toInstant(),
            createdAt = this[EmailOtpChallengesTable.createdAt].toInstant(),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
