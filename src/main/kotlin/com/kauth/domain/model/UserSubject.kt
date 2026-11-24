package com.kauth.domain.model

/** The rule a caller-chosen `sub` must meet, shared by backup import and the admin API (ADR-25). */
object UserSubject {
    const val MAX_LENGTH = 255

    /** Null when [subject] may be stored, otherwise why not. Never trimmed: a `sub` is matched exactly. */
    fun problemWith(subject: String): String? =
        when {
            subject.isBlank() -> "The subject must not be blank."
            subject.length > MAX_LENGTH -> "The subject must be $MAX_LENGTH characters or fewer."
            subject != subject.trim() -> "The subject must not start or end with whitespace."
            else -> null
        }
}
