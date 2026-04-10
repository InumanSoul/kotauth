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

    // Toast messages — success feedback after form saves
    const val TOAST_SETTINGS_SAVED = "Settings saved."
    const val TOAST_SECURITY_POLICY_SAVED = "Security policy saved."
    const val TOAST_BRANDING_SAVED = "Branding saved."
    const val TOAST_SMTP_SAVED = "SMTP settings saved."
    const val TOAST_IDP_SAVED = "Identity provider settings saved."
    const val TOAST_PROFILE_UPDATED = "Profile updated successfully."
    const val TOAST_PASSWORD_CHANGED = "Password changed successfully."
    const val TOAST_MFA_SETUP =
        "Authenticator set up successfully. Your account is now protected with two-factor authentication."
    const val TOAST_PROFILE_SAVED = "Profile saved."
    const val TOAST_RESET_EMAIL_SENT = "Password reset email sent successfully."
    const val TOAST_UNLOCKED = "Account unlocked successfully."
    const val TOAST_USER_DISABLED = "User disabled."
    const val TOAST_USER_ENABLED = "User enabled."
    const val TOAST_USER_SESSIONS_REVOKED = "All sessions revoked."
    const val TOAST_VERIFICATION_SENT = "Verification email sent."

    // Portal — navigation and shell
    const val PORTAL_SIGN_OUT = "Sign out"
    const val PORTAL_MY_ACCOUNT = "My Account"
    const val PORTAL_ACCOUNT = "Account"

    // Portal — connected accounts section (profile page)
    const val CONNECTED_ACCOUNTS_TITLE = "Connected accounts"
    const val CONNECTED_ACCOUNTS_SUBTITLE = "Social providers linked to your account"
    const val CONNECTED_ACCOUNTS_EMPTY = "No social accounts connected."

    // Invite Users feature
    const val INVITE_WELCOME_TITLE = "Welcome to"
    const val INVITE_ACCEPT_SUBTITLE = "Set a password to activate your account."
    const val INVITE_ACCEPT_SUBMIT = "Activate account"
    const val INVITE_ACCEPT_SUCCESS = "Your password has been set. Your account is now active."
    const val INVITE_ACCEPT_SIGN_IN = "Sign in to your account"
    const val INVITE_TOKEN_INVALID = "This invite link has expired or has already been used."
    const val INVITE_LOGIN_BLOCKED =
        "This account has a pending invitation. Check your email for the invite link, " +
            "or ask your administrator to resend it."
    const val INVITE_RADIO_SEND = "Send invite email"
    const val INVITE_RADIO_SEND_HINT = "An email will be sent with a link to set their password."
    const val INVITE_RADIO_PASSWORD = "Set password now"
    const val INVITE_RADIO_SMTP_HINT =
        "SMTP is not configured for this workspace. Configure it in Settings \u203a SMTP to enable invite emails."
    const val TOAST_INVITE_SENT = "Invite email sent."
    const val TOAST_INVITE_RESENT = "Invite resent."
    const val TOAST_INVITE_SEND_FAILED =
        "User created, but the invite email could not be sent. Resend it from this page."
    const val BADGE_INVITE_PENDING = "Invite pending"

    // Key rotation
    const val TOAST_KEY_ROTATED =
        "Signing key rotated. The previous key remains active for token verification until retired."
    const val TOAST_KEY_RETIRED = "Key retired. Tokens signed with this key will no longer be accepted."
}
