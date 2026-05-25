package com.kauth.domain.port

import com.kauth.domain.model.EmailOtpChallenge
import com.kauth.domain.model.UserId
import java.time.Instant

interface EmailOtpChallengeRepository {
    fun create(challenge: EmailOtpChallenge): EmailOtpChallenge

    fun findByChallengeId(challengeId: String): EmailOtpChallenge?

    fun incrementAttempts(id: Int): Int

    fun markConsumed(
        id: Int,
        consumedAt: Instant = Instant.now(),
    )

    /** Deletes any unconsumed challenges for the user — used on resend to invalidate the prior code. */
    fun deleteActiveByUser(userId: UserId): Int

    fun deleteExpired(now: Instant = Instant.now()): Int
}
