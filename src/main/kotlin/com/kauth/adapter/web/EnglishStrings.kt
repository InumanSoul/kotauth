package com.kauth.adapter.web

import java.lang.reflect.Modifier

@Suppress("unused")
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

    // -------------------------------------------------------------------------
    // Auth pages — shared chrome (page titles use {0} = workspace name)
    // -------------------------------------------------------------------------
    const val AUTH_PAGE_TITLE_LOGIN = "{0} | Sign In"
    const val AUTH_PAGE_TITLE_FORGOT = "{0} | Forgot Password"
    const val AUTH_PAGE_TITLE_RESET = "{0} | Reset Password"
    const val AUTH_PAGE_TITLE_INVITE = "{0} | Accept Invitation"
    const val AUTH_PAGE_TITLE_MFA = "{0} | Two-Factor Authentication"
    const val AUTH_COPYRIGHT_TEMPLATE = "© {0} {1}. All rights reserved. Powered by"
    const val AUTH_KOTAUTH_LINK = "KotAuth"
    const val AUTH_BACK_TO_SIGN_IN = "Back to sign in"
    const val AUTH_SHOW_PASSWORD = "Show password"
    const val AUTH_ICON_SHOW = "show"
    const val AUTH_ICON_HIDE = "hide"
    const val PASSWORD_MIN_PLACEHOLDER = "Minimum {0} characters"

    // Login page
    const val LOGIN_WELCOME_BACK = "Welcome back"
    const val LOGIN_SUBTITLE = "Sign in to your account"
    const val LOGIN_REGISTRATION_SUCCESS = "Account created successfully — please sign in."
    const val LOGIN_USERNAME = "Username"
    const val LOGIN_USERNAME_PLACEHOLDER = "Enter your username"
    const val LOGIN_PASSWORD_PLACEHOLDER = "Enter your password"
    const val LOGIN_SUBMIT = "Sign In"
    const val LOGIN_FORGOT_PASSWORD = "Forgot password?"
    const val LOGIN_MAGIC_LINK_LINK = "Sign in with an email link instead"
    const val LOGIN_NO_ACCOUNT = "Don't have an account? "
    const val LOGIN_CREATE_ONE = "Create one"
    const val LOGIN_OR_CONTINUE_WITH = "or continue with"
    const val LOGIN_CONTINUE_GOOGLE = "Continue with Google"
    const val LOGIN_CONTINUE_GITHUB = "Continue with GitHub"
    const val LOGIN_PROVIDER_GOOGLE = "Google"
    const val LOGIN_PROVIDER_GITHUB = "GitHub"

    // Forgot password page
    const val FORGOT_TITLE = "Forgot password"
    const val FORGOT_SUBTITLE = "Enter your email address and we'll send you a link to reset your password."
    const val FORGOT_SENT_MESSAGE =
        "If an account exists for that email address, you'll receive a reset link shortly. " +
            "Check your spam folder if you don't see it."
    const val FORGOT_EMAIL_LABEL = "Email address"
    const val FORGOT_EMAIL_PLACEHOLDER = "you@example.com"
    const val FORGOT_SEND_RESET = "Send reset link"

    // Reset password page
    const val RESET_TITLE = "Reset password"
    const val RESET_SUCCESS = "Password changed successfully."
    const val RESET_SIGN_IN_NEW = "Sign in with your new password"
    const val RESET_SUBTITLE = "Enter your new password below."
    const val RESET_CHANGE_BUTTON = "Change password"

    // MFA challenge page
    const val MFA_TAGLINE = "Two-factor authentication"
    const val MFA_VERIFY_IDENTITY = "Verify your identity"
    const val MFA_SUBTITLE = "Enter the 6-digit code from your authenticator app, or a recovery code."
    const val MFA_CODE_LABEL = "Authentication code"
    const val MFA_CODE_PLACEHOLDER = "Enter 6-digit code or recovery code"
    const val MFA_VERIFY_BUTTON = "Verify"

    /**
     * All `const val String` declarations in this object, keyed by their field name.
     * Used by the translation infrastructure as the English source of truth for
     * `EnglishOnlyTranslation` and as the fallback inside `BundleTranslation`.
     *
     * Computed once via reflection — the cost is amortized over the application's
     * lifetime. Function-based templates (e.g. parameterized placeholders) are
     * intentionally excluded; those use `{0}`-style placeholders in template strings.
     */
    val byKey: Map<String, String> by lazy {
        EnglishStrings::class.java.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(null) as String)
            }
    }
}
