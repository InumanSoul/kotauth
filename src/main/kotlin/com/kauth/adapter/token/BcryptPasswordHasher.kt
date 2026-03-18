package com.kauth.adapter.token

import at.favre.lib.crypto.bcrypt.BCrypt
import com.kauth.domain.port.PasswordHasher

/**
 * BCrypt adapter — implements the PasswordHasher port.
 *
 * Cost factor 12 is the recommended default as of 2024 — high enough to be
 * computationally expensive for attackers, low enough for normal auth flows.
 * Adjust upward as hardware improves.
 */
class BcryptPasswordHasher : PasswordHasher {
    override fun hash(rawPassword: String): String =
        BCrypt.withDefaults().hashToString(COST_FACTOR, rawPassword.toCharArray())

    override fun verify(
        rawPassword: String,
        hashedPassword: String,
    ): Boolean = BCrypt.verifyer().verify(rawPassword.toCharArray(), hashedPassword).verified

    companion object {
        private const val COST_FACTOR: Int = 12
    }
}
