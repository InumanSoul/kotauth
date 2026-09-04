package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.port.UserRepository
import kotlin.random.Random

/**
 * Builds a synthetic username for accounts provisioned without one.
 *
 * Integrators driving SCIM or the admin API should not have to invent an identifier
 * their users have never seen. The result is readable, unique within the tenant, and
 * editable by an admin afterwards.
 */
class UsernameGenerator(
    private val userRepository: UserRepository,
) {
    fun generate(
        tenantId: TenantId,
        givenName: String?,
        email: String,
    ): String {
        val stem = stemFrom(givenName) ?: stemFrom(email.substringBefore('@')) ?: NEUTRAL_STEM
        val truncated = stem.take(MAX_LENGTH - SUFFIX_LENGTH - 1)

        repeat(MAX_ATTEMPTS) {
            val candidate = "$truncated.${randomSuffix()}"
            if (isAvailable(tenantId, candidate)) return candidate
        }
        // Exhausting readable candidates is vanishingly unlikely; fall back to pure entropy,
        // still checked against both namespaces so this loop cannot manufacture a collision either.
        repeat(MAX_ATTEMPTS) {
            val candidate = "$NEUTRAL_STEM.${randomSuffix()}${randomSuffix()}"
            if (isAvailable(tenantId, candidate)) return candidate
        }
        // Astronomically unlikely: every entropy candidate collided too. Return one anyway —
        // createUser's downstream existsByUsername check turns a residual collision into a 409,
        // not a 500, so this is a safe last resort rather than a silent gap.
        return "$NEUTRAL_STEM.${randomSuffix()}${randomSuffix()}"
    }

    /** Checks BOTH namespaces so generation cannot manufacture the collision we reject elsewhere. */
    private fun isAvailable(
        tenantId: TenantId,
        candidate: String,
    ): Boolean =
        userRepository.findByUsernameIgnoreCase(tenantId, candidate) == null &&
            userRepository.findByEmail(tenantId, candidate) == null

    private fun stemFrom(raw: String?): String? =
        raw
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9._-]"), "")
            ?.trim('.', '_', '-')
            ?.takeIf { it.isNotEmpty() }

    private fun randomSuffix(): String =
        (1..SUFFIX_LENGTH)
            .map { ALPHABET[Random.nextInt(ALPHABET.length)] }
            .joinToString("")

    private companion object {
        const val MAX_LENGTH = 50
        const val SUFFIX_LENGTH = 6
        const val MAX_ATTEMPTS = 10
        const val NEUTRAL_STEM = "user"

        // Omits l, o, 0, 1 so a generated handle can be read aloud without ambiguity.
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}
