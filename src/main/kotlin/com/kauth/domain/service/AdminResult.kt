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
}
