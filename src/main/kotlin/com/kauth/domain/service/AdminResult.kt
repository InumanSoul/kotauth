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

    class Conflict(
        message: String,
    ) : AdminError(message)

    class Validation(
        message: String,
    ) : AdminError(message)

    data object SmtpRequired : AdminError("SMTP must be configured and enabled before disabling password sign-in.")

    data object NoMethodsEnabled : AdminError("At least one sign-in method must remain enabled.")
}
