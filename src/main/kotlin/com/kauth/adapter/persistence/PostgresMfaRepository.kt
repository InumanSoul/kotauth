package com.kauth.adapter.persistence

import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaMethod
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.port.MfaRepository
import com.kauth.infrastructure.EncryptionService
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
    private val encryptionService: EncryptionService,
) : MfaRepository {
    // -----------------------------------------------------------------------
    // Enrollments
    // -----------------------------------------------------------------------

    override fun findEnrollmentByUserId(
        userId: Int,
        method: String,
    ): MfaEnrollment? =
        transaction {
            MfaEnrollmentsTable
                .selectAll()
                .where {
                    (MfaEnrollmentsTable.userId eq userId) and
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
                    it[userId] = enrollment.userId
                    it[tenantId] = enrollment.tenantId
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

    override fun deleteEnrollmentsByUser(userId: Int): Unit =
        transaction {
            MfaEnrollmentsTable.deleteWhere { MfaEnrollmentsTable.userId eq userId }
        }

    // -----------------------------------------------------------------------
    // Recovery Codes
    // -----------------------------------------------------------------------

    override fun findUnusedRecoveryCodes(userId: Int): List<MfaRecoveryCode> =
        transaction {
            MfaRecoveryCodesTable
                .selectAll()
                .where {
                    (MfaRecoveryCodesTable.userId eq userId) and
                        (MfaRecoveryCodesTable.usedAt.isNull())
                }.map { it.toRecoveryCode() }
        }

    override fun saveRecoveryCodes(codes: List<MfaRecoveryCode>): Unit =
        transaction {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            MfaRecoveryCodesTable.batchInsert(codes) { code ->
                this[MfaRecoveryCodesTable.userId] = code.userId
                this[MfaRecoveryCodesTable.tenantId] = code.tenantId
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

    override fun deleteRecoveryCodesByUser(userId: Int): Unit =
        transaction {
            MfaRecoveryCodesTable.deleteWhere { MfaRecoveryCodesTable.userId eq userId }
        }

    // -----------------------------------------------------------------------
    // Row mappers
    // -----------------------------------------------------------------------

    private fun ResultRow.toEnrollment(): MfaEnrollment {
        val encryptedSecret = this[MfaEnrollmentsTable.secret]
        val decryptedSecret = decryptSecret(encryptedSecret)

        return MfaEnrollment(
            id = this[MfaEnrollmentsTable.id],
            userId = this[MfaEnrollmentsTable.userId],
            tenantId = this[MfaEnrollmentsTable.tenantId],
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
            userId = this[MfaRecoveryCodesTable.userId],
            tenantId = this[MfaRecoveryCodesTable.tenantId],
            codeHash = this[MfaRecoveryCodesTable.codeHash],
            usedAt = this[MfaRecoveryCodesTable.usedAt]?.toInstant(),
            createdAt = this[MfaRecoveryCodesTable.createdAt].toInstant(),
        )

    // -----------------------------------------------------------------------
    // Encryption helpers — transparent encrypt/decrypt for TOTP secrets
    // -----------------------------------------------------------------------

    private fun encryptSecret(plaintext: String): String =
        if (encryptionService.isAvailable) encryptionService.encrypt(plaintext) else plaintext

    private fun decryptSecret(stored: String): String =
        if (encryptionService.isAvailable) encryptionService.decrypt(stored) ?: stored else stored
}
