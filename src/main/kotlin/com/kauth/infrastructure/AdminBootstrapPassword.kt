package com.kauth.infrastructure

import com.kauth.domain.util.SecureTokens

object AdminBootstrapPassword {
    const val DEMO_PASSWORD = "Demo1234!"
    private const val OPERATOR_MIN_LENGTH = 12

    enum class Source { ENV, DEMO, GENERATED }

    data class Resolution(
        val password: String,
        val source: Source,
    )

    fun resolve(
        envPassword: String?,
        isDemoMode: Boolean,
        generator: () -> String = { SecureTokens.randomHex(16) },
    ): Resolution =
        when {
            !envPassword.isNullOrBlank() -> Resolution(envPassword, Source.ENV)
            isDemoMode -> Resolution(DEMO_PASSWORD, Source.DEMO)
            else -> Resolution(generator(), Source.GENERATED)
        }

    fun validateOperatorPassword(password: String): String? {
        if (password.length < OPERATOR_MIN_LENGTH) {
            return "KAUTH_BOOTSTRAP_ADMIN_PASSWORD must be at least $OPERATOR_MIN_LENGTH characters."
        }
        if (!password.any { it.isUpperCase() }) {
            return "KAUTH_BOOTSTRAP_ADMIN_PASSWORD must contain an uppercase letter."
        }
        if (!password.any { it.isLowerCase() }) {
            return "KAUTH_BOOTSTRAP_ADMIN_PASSWORD must contain a lowercase letter."
        }
        if (!password.any { it.isDigit() }) {
            return "KAUTH_BOOTSTRAP_ADMIN_PASSWORD must contain a digit."
        }
        return null
    }
}
