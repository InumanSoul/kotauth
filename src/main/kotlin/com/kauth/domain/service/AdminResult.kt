package com.kauth.domain.service

sealed class AdminResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AdminResult<T>()

    data class Failure(
        val error: AdminError,
    ) : AdminResult<Nothing>()
}

sealed class AdminError(
    val message: String,
) {
    class NotFound(
        message: String,
    ) : AdminError(message)

    /**
     * [kind] separates the two conflicts that share a 409 but have opposite remediations. A
     * [ConflictKind.DUPLICATE_VALUE] clears when the caller picks a different value; a
     * [ConflictKind.DEPENDENT_RESOURCES] never does, no matter what value is retried — the caller
     * has to resolve the dependents first. Protocol adapters that name the reason to the client
     * (SCIM's `scimType`) have to tell them apart or they send the caller into a retry loop.
     */
    class Conflict(
        message: String,
        val kind: ConflictKind = ConflictKind.DUPLICATE_VALUE,
    ) : AdminError(message)

    class Validation(
        message: String,
    ) : AdminError(message)

    data object SmtpRequired : AdminError("SMTP must be configured and enabled before disabling password sign-in.")

    data object NoMethodsEnabled : AdminError("At least one sign-in method must remain enabled.")
}

/** Why a [AdminError.Conflict] happened, for adapters that report the reason to a client. */
enum class ConflictKind {
    /** A value collides with one already stored; retrying with a different value clears it. */
    DUPLICATE_VALUE,

    /** Other rows still depend on the target; no retry clears it until they are resolved. */
    DEPENDENT_RESOURCES,
}
