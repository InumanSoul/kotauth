package com.kauth.domain.port

import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.model.UserId

/**
 * Port for MFA persistence — enrollments and recovery codes.
 *
 * All methods are tenant-scoped by design (enforced via the enrollment/code
 * entities which carry tenantId). The adapter encrypts TOTP secrets at rest
 * using [EncryptionService].
 */
interface MfaRepository {
    // ---- Enrollments ----

    fun findEnrollmentByUserId(
        userId: UserId,
        method: String = "totp",
    ): MfaEnrollment?

    fun findEnrollmentById(id: Int): MfaEnrollment?

    fun saveEnrollment(enrollment: MfaEnrollment): MfaEnrollment

    fun updateEnrollment(enrollment: MfaEnrollment): MfaEnrollment

    fun deleteEnrollmentsByUser(userId: UserId)

    // ---- Recovery Codes ----

    fun findUnusedRecoveryCodes(userId: UserId): List<MfaRecoveryCode>

    fun saveRecoveryCodes(codes: List<MfaRecoveryCode>)

    fun markRecoveryCodeUsed(codeId: Int)

    fun deleteRecoveryCodesByUser(userId: UserId)
}
