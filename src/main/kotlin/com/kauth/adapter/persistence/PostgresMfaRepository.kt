package com.kauth.adapter.persistence

import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaMethod
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.EncryptionPort
import com.kauth.domain.port.MfaRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * PostgreSQL adapter for MFA enrollments and recovery codes.
 *
 * TOTP secrets are encrypted at rest via [EncryptionService] (AES-256-GCM).
 * If encryption is unavailable, secrets are stored as plaintext — the
 * application warns about this at startup (see Application.kt).
 */
class PostgresMfaRepository(
    private val encryptionService: EncryptionPort,
) : MfaRepository {
    // -----------------------------------------------------------------------
    // Enrollments
    // -----------------------------------------------------------------------

    override fun findEnrollmentByUserId(
        userId: UserId,
        method: String,
    ): MfaEnrollment? =
        transaction {
            MfaEnrollmentsTable
                .selectAll()
                .where {
                    (MfaEnrollmentsTable.userId eq userId.value) and
                        (MfaEnrollmentsTable.method eq method)
                }.map { it.toEnrollment() }
                .singleOrNull()
        }

    override fun findEnrollmentById(id: Int): MfaEnrollment? =
        transaction {
            MfaEnrollmentsTable
                .selectAll()
                .where { MfaEnrollmentsTable.id eq id }
                .map { it.toEnrollment() }
                .singleOrNull()
        }

    override fun saveEnrollment(enrollment: MfaEnrollment): MfaEnrollment =
        transaction {
            val encryptedSecret = encryptSecret(enrollment.secret)
            val now = OffsetDateTime.now(ZoneOffset.UTC)

            val insertedId =
                MfaEnrollmentsTable.insert {
                    it[userId] = enrollment.userId.value
                    it[tenantId] = enrollment.tenantId.value
                    it[method] = enrollment.method.value
                    it[secret] = encryptedSecret
                    it[verified] = enrollment.verified
                    it[enabled] = enrollment.enabled
                    it[createdAt] = now
                    it[verifiedAt] =
                        enrollment.verifiedAt?.let { ts ->
                            OffsetDateTime.ofInstant(ts, ZoneOffset.UTC)
                        }
                } get MfaEnrollmentsTable.id

            enrollment.copy(id = insertedId, createdAt = now.toInstant())
        }

    override fun updateEnrollment(enrollment: MfaEnrollment): MfaEnrollment =
        transaction {
            MfaEnrollmentsTable.update({ MfaEnrollmentsTable.id eq enrollment.id!! }) {
                it[verified] = enrollment.verified
                it[enabled] = enrollment.enabled
                it[verifiedAt] =
                    enrollment.verifiedAt?.let { ts ->
                        OffsetDateTime.ofInstant(ts, ZoneOffset.UTC)
                    }
            }
            // Re-read to return consistent state
            MfaEnrollmentsTable
                .selectAll()
                .where { MfaEnrollmentsTable.id eq enrollment.id!! }
                .single()
                .toEnrollment()
        }

    override fun deleteEnrollmentsByUser(userId: UserId): Unit =
        transaction {
            MfaEnrollmentsTable.deleteWhere { MfaEnrollmentsTable.userId eq userId.value }
        }

    // -----------------------------------------------------------------------
    // Recovery Codes
    // -----------------------------------------------------------------------

    override fun findUnusedRecoveryCodes(userId: UserId): List<MfaRecoveryCode> =
        transaction {
            MfaRecoveryCodesTable
                .selectAll()
                .where {
                    (MfaRecoveryCodesTable.userId eq userId.value) and
                        (MfaRecoveryCodesTable.usedAt.isNull())
                }.map { it.toRecoveryCode() }
        }

    override fun saveRecoveryCodes(codes: List<MfaRecoveryCode>): Unit =
        transaction {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            MfaRecoveryCodesTable.batchInsert(codes) { code ->
                this[MfaRecoveryCodesTable.userId] = code.userId.value
                this[MfaRecoveryCodesTable.tenantId] = code.tenantId.value
                this[MfaRecoveryCodesTable.codeHash] = code.codeHash
                this[MfaRecoveryCodesTable.usedAt] = null
                this[MfaRecoveryCodesTable.createdAt] = now
            }
        }

    override fun markRecoveryCodeUsed(codeId: Int): Unit =
        transaction {
            MfaRecoveryCodesTable.update({ MfaRecoveryCodesTable.id eq codeId }) {
                it[usedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }

    override fun deleteRecoveryCodesByUser(userId: UserId): Unit =
        transaction {
            MfaRecoveryCodesTable.deleteWhere { MfaRecoveryCodesTable.userId eq userId.value }
        }

    // -----------------------------------------------------------------------
    // Row mappers
    // -----------------------------------------------------------------------

    private fun ResultRow.toEnrollment(): MfaEnrollment {
        val encryptedSecret = this[MfaEnrollmentsTable.secret]
        val decryptedSecret = decryptSecret(encryptedSecret)

        return MfaEnrollment(
            id = this[MfaEnrollmentsTable.id],
            userId = UserId(this[MfaEnrollmentsTable.userId]),
            tenantId = TenantId(this[MfaEnrollmentsTable.tenantId]),
            method = MfaMethod.fromValue(this[MfaEnrollmentsTable.method]),
            secret = decryptedSecret,
            verified = this[MfaEnrollmentsTable.verified],
            enabled = this[MfaEnrollmentsTable.enabled],
            createdAt = this[MfaEnrollmentsTable.createdAt].toInstant(),
            verifiedAt = this[MfaEnrollmentsTable.verifiedAt]?.toInstant(),
        )
    }

    private fun ResultRow.toRecoveryCode(): MfaRecoveryCode =
        MfaRecoveryCode(
            id = this[MfaRecoveryCodesTable.id],
            userId = UserId(this[MfaRecoveryCodesTable.userId]),
            tenantId = TenantId(this[MfaRecoveryCodesTable.tenantId]),
            codeHash = this[MfaRecoveryCodesTable.codeHash],
            usedAt = this[MfaRecoveryCodesTable.usedAt]?.toInstant(),
            createdAt = this[MfaRecoveryCodesTable.createdAt].toInstant(),
        )

    private fun encryptSecret(plaintext: String): String = encryptionService.encrypt(plaintext)

    private fun decryptSecret(stored: String): String = encryptionService.decrypt(stored) ?: stored
}
