package com.kauth.adapter.web

/**
 * Canonical English strings for user-facing UI text.
 *
 * Centralized here to prepare for i18n (v2.x). Each string is the reference
 * value that translators will use as the source of truth.
 *
 * Strings are extracted incrementally as views are touched, do not attempt
 * to extract all strings in one pass.
 */

object EnglishStrings {
    // Password fields
    const val PASSWORD = "Password"
    const val NEW_PASSWORD = "New password"
    const val CONFIRM_PASSWORD = "Confirm Password"
    const val CONFIRM_NEW_PASSWORD = "Confirm new password"
    const val CONFIRM_PASSWORD_PLACEHOLDER = "Repeat your new password"
    const val PASSWORD_HINT_USER_CAN_CHANGE = "The user can change it after login."

    fun passwordMinPlaceholder(minLength: Int) = "Minimum $minLength characters"

    // Password validation (client-side — mirrored in password-validation.js)
    const val PASSWORDS_DO_NOT_MATCH = "Passwords do not match"
}
