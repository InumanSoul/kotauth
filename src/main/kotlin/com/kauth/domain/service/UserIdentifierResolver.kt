package com.kauth.domain.service

import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.port.UserRepository

/** Outcome of matching a submitted login identifier against a tenant's users. */
sealed interface IdentifierResolution {
    data class Found(
        val user: User,
    ) : IdentifierResolution

    data object NotFound : IdentifierResolution

    /** A username and an email matched two different accounts. Treated as a failure. */
    data object Ambiguous : IdentifierResolution
}

/**
 * Matches what a user typed against a tenant's accounts, per the tenant's
 * [LoginIdentifierMode].
 *
 * Not a port: it inverts no external dependency, it orchestrates over [UserRepository].
 */
class UserIdentifierResolver(
    private val userRepository: UserRepository,
) {
    fun resolve(
        tenantId: TenantId,
        mode: LoginIdentifierMode,
        submitted: String,
    ): IdentifierResolution {
        val value = submitted.trim()
        // Guards the legacy `email NOT NULL DEFAULT ''` row from being reached by a blank submit.
        if (value.isEmpty()) return IdentifierResolution.NotFound

        return when (mode) {
            LoginIdentifierMode.USERNAME ->
                userRepository.findByUsername(tenantId, value).toResolution()

            LoginIdentifierMode.EMAIL ->
                userRepository.findByEmail(tenantId, value).toResolution()

            LoginIdentifierMode.EITHER -> {
                // Both lookups always run. Short-circuiting would make a miss cost two
                // queries and a hit one, which is an observable timing difference.
                val byUsername = userRepository.findByUsername(tenantId, value)
                val byEmail = userRepository.findByEmail(tenantId, value)
                when {
                    byUsername != null && byEmail != null && byUsername.id != byEmail.id ->
                        IdentifierResolution.Ambiguous
                    else -> (byUsername ?: byEmail).toResolution()
                }
            }
        }
    }

    private fun User?.toResolution(): IdentifierResolution =
        if (this == null) IdentifierResolution.NotFound else IdentifierResolution.Found(this)
}
