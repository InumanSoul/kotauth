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

    /** Trims and lowercases. Callers must still validate the result against [validate]. */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /** Why [validate] rejected an already-[normalize]d value. */
    enum class Violation { INVALID_FORMAT, TOO_LONG }

    /**
     * The single entry point for "is this a storable username" — checks both [USERNAME_PATTERN]
     * and [MAX_LENGTH] in one place, so every write path enforces the exact same bound instead of
     * each one duplicating (and risking drifting from) the checks. [normalized] must already have
     * been through [normalize]. Returns `null` when valid.
     */
    fun validate(normalized: String): Violation? =
        when {
            !normalized.matches(USERNAME_PATTERN) -> Violation.INVALID_FORMAT
            normalized.length > MAX_LENGTH -> Violation.TOO_LONG
            else -> null
        }

    /** Convenience boolean form of [validate] for callers that don't need to distinguish why. */
    fun isValid(normalized: String): Boolean = validate(normalized) == null

    /**
     * Rewrites a legacy username into a storable one, reproducing exactly what migration V66
     * applies to rows that predate the format rule: lowercase and trim, collapse each run of
     * forbidden characters to a single `.`, then strip leading and trailing `.`, `_` and `-`.
     *
     * This is deliberately NOT what [normalize] does, and not what any interactive write path
     * does — those reject `"john doe"` so a human fixes it. It exists for the one caller that
     * has no human to ask: restoring a backup taken before v1.24.0, where the same values V66
     * rewrote in place on the upgrade path would otherwise dead-end an import that
     * [com.kauth.domain.model.BackupCompatibilityMatrix] declares supported.
     *
     * Can still return an empty or invalid result — a username made entirely of characters
     * outside the class (a non-Latin script) collapses to `""` — so callers must validate the
     * result rather than trusting it. V66 aborts in that case and so does the importer.
     */
    fun rewriteLegacy(raw: String): String =
        normalize(raw)
            .replace(Regex("[^a-z0-9._@+-]+"), ".")
            .replace(Regex("^[._-]+"), "")
            .replace(Regex("[._-]+$"), "")
}
