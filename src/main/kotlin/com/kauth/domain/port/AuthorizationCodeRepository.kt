package com.kauth.domain.port

import com.kauth.domain.model.AuthorizationCode
import java.time.Instant

/**
 * Port — authorization code storage.
 * Implemented by [PostgresAuthorizationCodeRepository].
 */
interface AuthorizationCodeRepository {
    /** Persists a newly issued authorization code. */
    fun save(code: AuthorizationCode): AuthorizationCode

    /** Retrieves a code by its value. Returns null if not found. */
    fun findByCode(code: String): AuthorizationCode?

    /** Marks a code as consumed (used_at = now). Must be called exactly once per code. */
    fun markUsed(
        code: String,
        usedAt: Instant = Instant.now(),
    )
}
