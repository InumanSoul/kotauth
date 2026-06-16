package com.kauth.domain.service

sealed class SelfServiceResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SelfServiceResult<T>()

    data class Failure(
        val error: SelfServiceError,
    ) : SelfServiceResult<Nothing>()
}

sealed class SelfServiceError(
    val message: String,
) {
    class NotFound(
        message: String,
    ) : SelfServiceError(message)

    class Validation(
        message: String,
    ) : SelfServiceError(message)

    class Unauthorized(
        message: String,
    ) : SelfServiceError(message)

    class TokenExpired(
        message: String,
    ) : SelfServiceError(message)

    class TokenInvalid(
        message: String,
    ) : SelfServiceError(message)

    class SmtpNotConfigured(
        message: String,
    ) : SelfServiceError(message)

    class PasswordLoginDisabled(
        message: String = "Password sign-in is disabled for this workspace.",
    ) : SelfServiceError(message)
}
