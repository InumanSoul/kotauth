package com.kauth.fakes

import com.kauth.domain.model.MfaEnrollment
import com.kauth.domain.model.MfaRecoveryCode
import com.kauth.domain.port.MfaRepository
import java.time.Instant

/**
 * In-memory MfaRepository for unit tests.
 * Stores one enrollment per userId + a list of recovery codes.
 */
class FakeMfaRepository : MfaRepository {

    private val enrollments   = mutableMapOf<Int, MfaEnrollment>()   // keyed by userId
    private val recoveryCodes = mutableListOf<MfaRecoveryCode>()
    private var nextEnrollmentId = 1
    private var nextCodeId = 1

    fun clear() {
        enrollments.clear()
        recoveryCodes.clear()
        nextEnrollmentId = 1
        nextCodeId = 1
    }

    override fun findEnrollmentByUserId(userId: Int, method: String) = enrollments[userId]

    override fun findEnrollmentById(id: Int) = enrollments.values.find { it.id == id }

    override fun saveEnrollment(enrollment: MfaEnrollment): MfaEnrollment {
        val saved = enrollment.copy(id = nextEnrollmentId++)
        enrollments[saved.userId] = saved
        return saved
    }

    override fun updateEnrollment(enrollment: MfaEnrollment): MfaEnrollment {
        enrollments[enrollment.userId] = enrollment
        return enrollment
    }

    override fun deleteEnrollmentsByUser(userId: Int) { enrollments.remove(userId) }

    override fun findUnusedRecoveryCodes(userId: Int) =
        recoveryCodes.filter { it.userId == userId && it.usedAt == null }

    override fun saveRecoveryCodes(codes: List<MfaRecoveryCode>) {
        codes.forEach { recoveryCodes.add(it.copy(id = nextCodeId++)) }
    }

    override fun markRecoveryCodeUsed(codeId: Int) {
        val idx = recoveryCodes.indexOfFirst { it.id == codeId }
        if (idx >= 0) recoveryCodes[idx] = recoveryCodes[idx].copy(usedAt = Instant.now())
    }

    override fun deleteRecoveryCodesByUser(userId: Int) {
        recoveryCodes.removeAll { it.userId == userId }
    }
}
