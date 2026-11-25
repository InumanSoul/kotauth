package com.kauth.domain.service

import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.UserRepository

/** What a rename must pass, shared by the administrator and self-service paths so they can't drift. */
class UsernameRules(
    private val users: UserRepository,
    private val collisionCheck: IdentifierCollisionCheck,
) {
    sealed interface Check {
        data class Ok(
            val username: String,
        ) : Check

        data class Invalid(
            val message: String,
        ) : Check

        data class Taken(
            val message: String,
        ) : Check
    }

    /** [username] must already be normalized; [email] is the address the user will hold afterwards. */
    fun checkRename(
        tenantId: TenantId,
        userId: UserId,
        username: String,
        email: String,
    ): Check {
        if (username.isBlank()) return Check.Invalid("Username is required.")
        when (UsernamePolicy.validate(username)) {
            UsernamePolicy.Violation.INVALID_FORMAT ->
                return Check.Invalid(
                    "Username may only contain letters, digits, dots, underscores, hyphens, @, and +.",
                )
            UsernamePolicy.Violation.TOO_LONG ->
                return Check.Invalid("Username must be ${UsernamePolicy.MAX_LENGTH} characters or fewer.")
            null -> Unit
        }
        if (users.existsByUsername(tenantId, username)) {
            return Check.Taken("Username '$username' is already taken.")
        }
        collisionCheck
            .check(tenantId, username, email, excludingUserId = userId)
            ?.let { return Check.Invalid(it) }
        return Check.Ok(username)
    }
}
