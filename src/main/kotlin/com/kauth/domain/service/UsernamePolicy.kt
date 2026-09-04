package com.kauth.domain.service

/**
 * The single definition of what a normalized username looks like, and how to get one.
 *
 * Product rule: usernames are ALWAYS stored trimmed, lowercased, and matching
 * [USERNAME_PATTERN]. Every write path normalizes first, then validates — so `"Dave"`
 * becomes `"dave"` and is accepted, while `"john doe"` is rejected rather than silently
 * rewritten, because a human is expected to fix it by hand.
 *
 * Shared by every service that can create or rename a username — [AdminUserService],
 * [AuthService], [SocialLoginService], [EmailOtpService], and [BackupImporterService] —
 * so the character class cannot drift between admin-created, self-registered,
 * socially-provisioned, JIT-provisioned, and restored users.
 */
object UsernamePolicy {
    val USERNAME_PATTERN = Regex("[a-zA-Z0-9._@+-]+")

    /** Matches the `users.username` column width (`VARCHAR(255)`, widened by V60). */
    const val MAX_LENGTH = 255

    /** Trims and lowercases. Callers must still validate the result against [USERNAME_PATTERN]. */
    fun normalize(raw: String): String = raw.trim().lowercase()
}
