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
    const val TOAST_SIGN_IN_METHODS_SAVED = "Sign-in methods updated."
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
    const val TOAST_PASSKEY_REVOKED = "Passkey revoked."
    const val TOAST_PASSKEYS_RESET = "All passkeys reset for this user."
    const val TOAST_MFA_RESET = "MFA reset for this user."

    // Portal — navigation and shell
    const val PORTAL_SIGN_OUT = "Sign out"
    const val PORTAL_MY_ACCOUNT = "My Account"
    const val PORTAL_ACCOUNT = "Account"

    // Portal — app launcher
    const val LAUNCHER_NAV = "Applications"
    const val LAUNCHER_PAGE_TITLE = "Applications"
    const val LAUNCHER_PAGE_SUBTITLE = "Apps you can access in this workspace"
    const val LAUNCHER_OPEN_IN_NEW_TAB = "Open in a new tab"
    const val LAUNCHER_EMPTY_TITLE = "No applications available"
    const val LAUNCHER_EMPTY_BODY =
        "Ask your workspace admin to grant you access to one or more applications."

    // Portal — login page (account portal entry)
    const val PORTAL_LOGIN_TITLE = "Account"
    const val PORTAL_LOGIN_SUBTITLE = "Sign in to manage your account"
    const val PORTAL_LOGIN_USERNAME = "Username"
    const val PORTAL_LOGIN_USERNAME_PLACEHOLDER = "Enter your username"
    const val PORTAL_LOGIN_PASSWORD = "Password"
    const val PORTAL_LOGIN_PASSWORD_PLACEHOLDER = "Enter your password"
    const val PORTAL_LOGIN_SUBMIT = "Sign in"
    const val PORTAL_LOGIN_FORGOT = "Forgot password?"

    // Portal — navigation labels
    const val PORTAL_NAV_PROFILE = "Profile"
    const val PORTAL_NAV_SECURITY = "Security"
    const val PORTAL_NAV_SECURITY_OVERVIEW = "Overview"
    const val PORTAL_NAV_MFA = "Two-Factor Auth"
    const val PORTAL_NAV_SESSIONS = "Sessions"
    const val PORTAL_TOPBAR_TITLE = "Account settings"

    // Portal — profile page
    const val PORTAL_PROFILE_TITLE = "Profile"
    const val PORTAL_PROFILE_SUBTITLE = "Manage your personal information"
    const val PORTAL_PROFILE_USERNAME = "Username"
    const val PORTAL_PROFILE_USERNAME_HINT = "Username cannot be changed after account creation."
    const val PORTAL_PROFILE_EMAIL = "Email address"
    const val PORTAL_PROFILE_FULL_NAME = "Full name"
    const val PORTAL_PROFILE_SAVE = "Save changes"
    const val PORTAL_DANGER_ZONE = "Danger zone"
    const val PORTAL_DELETE_ACCOUNT_TITLE = "Delete account"
    const val PORTAL_DELETE_ACCOUNT_DESC =
        "Permanently deletes your account, profile, and all associated data. This cannot be undone."
    const val PORTAL_DELETE_ACCOUNT_BUTTON = "Delete account"
    const val PORTAL_DELETE_CONFIRM_PREFIX = "Type "
    const val PORTAL_DELETE_CONFIRM_BUTTON = "Confirm delete"

    // Portal — security page
    const val PORTAL_SECURITY_TITLE = "Security"
    const val PORTAL_SECURITY_SUBTITLE = "Manage your authentication settings"
    const val PORTAL_CHANGE_PASSWORD_SUBTITLE = "Change your account password"
    const val PORTAL_SESSIONS_REVOKE_OTHERS_CONFIRM =
        "Sign out of all other sessions? Only your current session will remain active."
    const val PORTAL_SESSIONS_REVOKE_CONFIRM =
        "Revoke this session? The user will be signed out immediately."
    const val PORTAL_SECURITY_CHANGE_PASSWORD = "Change password"
    const val PORTAL_SECURITY_CURRENT_PASSWORD = "Current password"
    const val PORTAL_SECURITY_SIGNOUT_NOTE = "Changing your password signs you out of all active sessions"
    const val PORTAL_SECURITY_ACTIVE_SESSIONS = "Active sessions"
    const val PORTAL_SECURITY_SESSIONS_SUBTITLE = "Devices currently signed into your account"
    const val PORTAL_SECURITY_REVOKE_OTHERS = "Revoke all others"
    const val PORTAL_SECURITY_NO_SESSIONS = "No active sessions found."
    const val PORTAL_SECURITY_TABLE_DEVICE = "Device / IP"
    const val PORTAL_SECURITY_TABLE_STARTED = "Started"
    const val PORTAL_SECURITY_TABLE_EXPIRES = "Expires"
    const val PORTAL_SECURITY_CURRENT_PILL = "Current"
    const val PORTAL_SECURITY_REVOKE = "Revoke"

    // Portal — MFA page
    const val PORTAL_MFA_TITLE = "Two-Factor Authentication"
    const val PORTAL_MFA_SUBTITLE = "Protect your account with an authenticator app"
    const val PORTAL_MFA_AUTHENTICATOR_APP = "Authenticator app"
    const val PORTAL_MFA_ACTIVE = "Active"
    const val PORTAL_MFA_PROTECTING = "Two-factor authentication is protecting your account."
    const val PORTAL_MFA_SIGNIN_HINT =
        "When you sign in you'll be asked for a 6-digit code from your authenticator app."
    const val PORTAL_MFA_RECOVERY_HINT =
        "Recovery codes were displayed once when you set up two-factor authentication. " +
            "To generate new codes, remove and re-enable two-factor authentication."
    const val PORTAL_MFA_REMOVE = "Remove authenticator"
    const val PORTAL_MFA_REMOVE_WARNING =
        "This will remove your authenticator app and disable two-factor authentication. " +
            "Your account will only be protected by your password."
    const val PORTAL_MFA_REMOVE_CONFIRM = "Yes, remove authenticator"
    const val PORTAL_MFA_CANCEL = "Cancel"
    const val PORTAL_MFA_NOT_CONFIGURED = "Not configured"
    const val PORTAL_MFA_SETUP_INTRO =
        "Use an authenticator app to generate one-time codes. Once enabled, you'll need your phone " +
            "every time you sign in. Save your recovery codes somewhere safe before finishing setup."
    const val PORTAL_MFA_COMPATIBLE_APPS = "Compatible Apps"
    const val PORTAL_MFA_SETUP_BUTTON = "Set up authenticator"
    const val PORTAL_MFA_SCAN_INSTRUCTION =
        "Open your authenticator app and scan the QR code below to add your account."
    const val PORTAL_MFA_MANUAL_KEY = "Can't scan? Enter this key manually: "
    const val PORTAL_MFA_RECOVERY_CODES_INTRO =
        "If you ever lose access to your authenticator app, use one of these codes to sign in. " +
            "Each code works only once."
    const val PORTAL_MFA_SAVE_CODES =
        "Save these codes now — they won't be shown again after you leave this page."
    const val PORTAL_MFA_COPY_CODES = "Copy codes"
    const val PORTAL_MFA_VERIFY_INSTRUCTION =
        "Enter the 6-digit code shown in your authenticator app to confirm everything is working."
    const val PORTAL_MFA_VERIFICATION_CODE = "Verification code"
    const val PORTAL_MFA_CONFIRM_SETUP = "Confirm setup"

    // Portal — MFA page intro (used on the Security overview card)
    const val PORTAL_MFA_INTRO = "Protect your account with a time-based one-time code from an authenticator app."

    // Portal — Security overview card shared action label
    const val PORTAL_SECURITY_MANAGE = "Manage"

    // Portal — passkeys page
    const val PORTAL_NAV_PASSKEYS = "Passkeys"
    const val PORTAL_PASSKEYS_TITLE = "Passkeys"
    const val PORTAL_PASSKEYS_INTRO = "Sign in without a password using your device's biometrics or a hardware key."

    // Portal — sessions page intro (used on the Security overview card)
    const val PORTAL_SESSIONS_INTRO = "See where you're signed in and revoke sessions."
    const val PORTAL_PASSKEYS_ADD_BUTTON = "Add a passkey"
    const val PORTAL_PASSKEYS_EMPTY_STATE = "You have no passkeys enrolled yet."
    const val PORTAL_PASSKEYS_ADDED_ON = "Added"
    const val PORTAL_PASSKEYS_LAST_USED = "Last used"
    const val PORTAL_PASSKEYS_RENAME = "Rename"
    const val PORTAL_PASSKEYS_REVOKE = "Remove"
    const val PASSKEY_ADD_DIALOG_TITLE = "Add a passkey"
    const val PASSKEY_RENAME_DIALOG_TITLE = "Rename passkey"
    const val PASSKEY_REMOVE_CONFIRM = "Remove passkey \"{name}\"?"

    // Passkey inline components — error messages and form labels
    const val PASSKEY_CANCEL_BUTTON = "Cancel"
    const val PASSKEY_ERROR_ALREADY_ENROLLED = "That passkey is already registered on this account."
    const val PASSKEY_ERROR_CANCELLED = "Passkey action was cancelled."
    const val PASSKEY_ERROR_GENERIC = "We couldn't complete that. Please try again."
    const val PASSKEY_ERROR_UNSUPPORTED = "This browser doesn't support passkeys."
    const val PASSKEY_ERROR_VERIFICATION = "Your device could not be verified. Try a different passkey."
    const val PASSKEY_NAME_LABEL = "Passkey name"
    const val PASSKEY_NAME_PLACEHOLDER = "e.g. My phone"
    const val PASSKEY_SAVE_BUTTON = "Save"

    // Portal — confirm dialog
    const val PORTAL_CONFIRM_TITLE = "Confirm"
    const val PORTAL_CONFIRM_CANCEL = "Cancel"
    const val PORTAL_CONFIRM_OK = "Confirm"

    // Portal — user menu (avatar dropdown)
    const val PORTAL_USER_MENU_OPEN = "Open user menu"

    // Portal — connected accounts section (profile page)
    const val CONNECTED_ACCOUNTS_TITLE = "Connected accounts"
    const val CONNECTED_ACCOUNTS_SUBTITLE = "Social providers linked to your account"
    const val CONNECTED_ACCOUNTS_EMPTY = "No social accounts connected."

    // Impersonation banner (portal) — shown when an admin acts as a tenant user
    const val IMPERSONATION_BANNER_LEAD = "Impersonating"
    const val IMPERSONATION_BANNER_SIGNED_IN_AS = "Signed in as"
    const val IMPERSONATION_BANNER_AUDITED = "actions are recorded"
    const val IMPERSONATION_BANNER_END = "End session"
    const val IMPERSONATION_DISABLED_TOOLTIP = "Unavailable during impersonation"

    // Admin user-detail page — Impersonate action
    const val IMPERSONATE_BUTTON = "Impersonate user"
    const val IMPERSONATE_CONFIRM =
        "You will be signed into the portal as this user. " +
            "All actions are recorded in the audit log."
    const val IMPERSONATE_REPLACE_CONFIRM =
        "You are already impersonating another user. " +
            "Starting a new session will end that one. Continue?"
    const val IMPERSONATE_FAILED_DISABLED = "Cannot impersonate a disabled user."
    const val IMPERSONATE_FAILED_LOCKED = "Cannot impersonate a temporarily locked user."
    const val IMPERSONATE_FAILED_GENERIC = "Could not start impersonation."

    // Disabled-state tooltips on the Impersonate button
    const val IMPERSONATE_BLOCKED_DISABLED = "Cannot impersonate a disabled user."
    const val IMPERSONATE_BLOCKED_LOCKED = "Cannot impersonate a temporarily locked user."
    const val IMPERSONATE_BLOCKED_PENDING = "Cannot impersonate a user who hasn't activated their account."

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

    // APIs / RFC 8707 Resource Indicators (v1.18.0)
    const val API_NAV_LABEL = "APIs"
    const val API_PAGE_TITLE = "APIs"
    const val API_PAGE_SUBTITLE =
        "Register the APIs that your clients request audience-targeted tokens for. Each API has an " +
            "identifier (the `aud` claim) that resource servers use to validate incoming tokens."
    const val API_EMPTY_TITLE = "No APIs yet"
    const val API_EMPTY_BODY =
        "Register an API to start issuing audience-targeted M2M tokens. Authorize clients per API to " +
            "control which audiences each caller can request."
    const val API_LIST_COLUMN_NAME = "Name"
    const val API_LIST_COLUMN_IDENTIFIER = "Audience"
    const val API_LIST_COLUMN_STATUS = "Status"
    const val API_ADD = "Register API"
    const val API_FORM_NEW_TITLE = "Register API"
    const val API_FORM_EDIT_TITLE = "Edit API"
    const val API_FIELD_IDENTIFIER = "Audience identifier"
    const val API_FIELD_IDENTIFIER_HINT_NEW =
        "Used as the JWT `aud` claim. Use a URI like https://api.example.com or a stable slug like payment-api. " +
            "Immutable after creation."
    const val API_FIELD_IDENTIFIER_HINT_LOCKED = "Immutable after creation."
    const val API_FIELD_NAME = "Name"
    const val API_FIELD_NAME_HINT = "A human-readable name shown in the admin UI only."
    const val API_FIELD_DESCRIPTION = "Description"
    const val API_FIELD_DESCRIPTION_HINT = "Optional. Internal notes about what this API is for."
    const val API_AUTHORIZED_CLIENTS_HEADING = "Authorized APIs"
    const val API_AUTHORIZED_CLIENTS_HINT =
        "Tick each API this client is allowed to request as the audience of a client-credentials token."
    const val API_AUTHORIZED_CLIENTS_EMPTY_TITLE = "No APIs in this workspace yet"
    const val API_AUTHORIZED_CLIENTS_EMPTY_BODY =
        "Register an API under Settings → APIs, then return here to authorize it for this client."
    const val API_AUTHORIZED_CLIENTS_EMPTY_CTA = "Register API"
    const val API_AUTHORIZED_CLIENTS_ALL = "All"
    const val API_AUTHORIZED_CLIENTS_NONE = "None"
    const val TOAST_API_CREATED = "API registered."
    const val TOAST_API_UPDATED = "API updated."
    const val TOAST_API_DISABLED = "API disabled."
    const val TOAST_API_ENABLED = "API enabled."
    const val TOAST_API_DELETED = "API deleted."
    const val TOAST_AUTHORIZED_APIS_UPDATED = "Authorized APIs updated."
    const val RESOURCE_SERVER_SCOPES_LABEL = "Scopes (one per line)"
    const val RESOURCE_SERVER_SCOPES_HINT =
        "Tokens issued for this API will be narrowed to scopes in this list. " +
            "Leave empty to disable narrowing."

    // Tenant backup / restore (v1.9.0)
    const val BACKUP_NAV_LABEL = "Backup"
    const val BACKUP_PAGE_TITLE = "Backup workspace"
    const val BACKUP_PAGE_SUBTITLE =
        "Export this workspace to an encrypted file. Use the file to restore the workspace " +
            "elsewhere, or to clone it into a staging environment."
    const val BACKUP_PASSPHRASE_LABEL = "Backup passphrase"
    const val BACKUP_PASSPHRASE_HINT =
        "16+ characters. The exported file is unreadable without it. " +
            "Kotauth never stores this passphrase — keep it somewhere safe."
    const val BACKUP_CONFIRM_PASSPHRASE_LABEL = "Confirm passphrase"
    const val BACKUP_INCLUDE_SIGNING_KEYS_LABEL = "Include RSA signing keys"
    const val BACKUP_INCLUDE_SIGNING_KEYS_DESC =
        "Default off. Including the private signing keys lets the destination tenant verify tokens " +
            "issued before the backup; turn this on only when migrating a live tenant to a new deployment."
    const val BACKUP_INCLUDE_AUDIT_LOG_LABEL = "Include audit log"
    const val BACKUP_INCLUDE_AUDIT_LOG_DESC =
        "Default off. The audit log can be large and may be subject to retention policy."
    const val BACKUP_CONFIRM_SLUG_LABEL = "Type the workspace slug to confirm"
    const val BACKUP_CONFIRM_SLUG_HINT_PREFIX = "Type "
    const val BACKUP_CONFIRM_SLUG_HINT_SUFFIX = " exactly to enable the export button."
    const val BACKUP_DOWNLOAD_BUTTON = "Export & download"

    // Export-page top-of-form scope notice — informational, non-blocking
    const val BACKUP_SCOPE_NOTICE_TITLE = "Some secrets will not be included"
    const val BACKUP_SCOPE_NOTICE_BODY =
        "OAuth client secrets, social provider secrets, SMTP password, MFA seeds, and active sessions are " +
            "redacted from every export. After importing this workspace elsewhere, an operator will need to " +
            "regenerate them before the destination is fully functional."

    // Import-page top-of-form recovery notice — actionable list of what to redo after import
    const val IMPORT_RECOVERY_NOTICE_TITLE = "After import, regenerate these secrets"
    const val IMPORT_RECOVERY_NOTICE_BODY =
        "These items are not in the backup file and must be reconfigured before the imported workspace " +
            "is fully functional:"
    const val IMPORT_RECOVERY_OAUTH_SECRETS =
        "OAuth client secrets — regenerate per application after import."
    const val IMPORT_RECOVERY_SOCIAL_SECRETS =
        "Social provider client secrets — re-enter in Settings › Identity Providers."
    const val IMPORT_RECOVERY_SMTP_PASSWORD =
        "SMTP password — reconfigure in Settings › SMTP."
    const val IMPORT_RECOVERY_MFA_SEEDS =
        "MFA TOTP seeds and recovery codes — users must re-enroll after import."
    const val IMPORT_RECOVERY_SESSIONS =
        "Active sessions, authorization codes, and magic-link tokens were never exported."

    const val IMPORT_PAGE_TITLE = "Import workspace from backup"
    const val IMPORT_PAGE_SUBTITLE =
        "Restore a previously exported workspace as a new tenant on this deployment."
    const val IMPORT_FILE_LABEL = "Backup file (.json.enc)"
    const val IMPORT_NEW_SLUG_LABEL = "New workspace slug"
    const val IMPORT_NEW_SLUG_HINT =
        "Lowercase letters, digits, and dashes; 2–50 characters. Must not match an existing workspace."
    const val IMPORT_PASSPHRASE_LABEL = "Backup passphrase"
    const val IMPORT_PASSPHRASE_HINT = "The passphrase used at export time."
    const val IMPORT_SUBMIT_BUTTON = "Import workspace"
    const val IMPORT_LINK_FROM_LIST = "Import from backup"
    const val IMPORT_LINK_FROM_CREATE = "Restoring from a backup? Import instead."

    // Authentication methods card (workspace security settings — v1.10)
    const val AUTH_METHODS_GROUP_SIGN_IN = "Sign-in methods"
    const val AUTH_METHODS_TABLE_COL_METHOD = "Method"
    const val AUTH_METHODS_TABLE_COL_ENABLED = "Enabled"
    const val AUTH_METHODS_TABLE_COL_NOTES = "Notes"
    const val AUTH_METHODS_GROUP_LIMITS = "Limits"
    const val AUTH_METHODS_MAGIC_LINK_LABEL = "Allow sign-in via email magic link"
    const val AUTH_METHODS_MAGIC_LINK_DESC =
        "Users can request a one-time sign-in link delivered to their email. " +
            "Links are single-use and expire after the window set below. MFA still " +
            "required when enrolled. Requires SMTP to be configured."
    const val AUTH_METHODS_MAGIC_LINK_TTL_LABEL = "Magic link expiry"
    const val AUTH_METHODS_MAGIC_LINK_TTL_HINT =
        "Minutes before a magic link expires. 1 to 1440 (default 15). " +
            "Raise it for slow corporate mail relays; lower it for high-assurance tenants."
    const val AUTH_METHODS_EMAIL_OTP_LOGIN_LABEL =
        "Allow sign-in via Email OTP code"
    const val AUTH_METHODS_EMAIL_OTP_LOGIN_DESC =
        "Adds a \"Sign in with email code\" option to the hosted login page. The user " +
            "enters their email, receives a 6-digit code, and types it in. Independent " +
            "of the magic link toggle above. Requires SMTP."
    const val AUTH_METHODS_EMAIL_OTP_SIGNUP_LABEL =
        "Allow sign-up via Email OTP admin API"
    const val AUTH_METHODS_EMAIL_OTP_SIGNUP_DESC =
        "When enabled, the OTP admin API creates a passwordless account if the email " +
            "does not exist yet. Leave off unless an external onboarding flow controls " +
            "user creation. Enabling this opens a new account-creation channel."
    const val AUTH_METHODS_EMAIL_OTP_LOCKOUT_LABEL =
        "OTP lockout after failed attempts"
    const val AUTH_METHODS_EMAIL_OTP_LOCKOUT_HINT =
        "Locks the account after this many failed OTP attempts across all active challenges. " +
            "0 disables this guard. Each individual challenge still allows up to 5 attempts."
    const val AUTH_METHODS_EMAIL_OTP_SMTP_WARN =
        "SMTP is not configured for this workspace. OTP emails will fail silently until " +
            "you configure SMTP under workspace settings."

    // Sign-in Methods grid rows (v1.20.1 — SecurityMethodsService)
    const val AUTH_METHOD_PASSWORD_LABEL = "Password"
    const val AUTH_METHOD_PASSKEY_LABEL = "Passkey"
    const val AUTH_METHOD_MAGIC_LINK_LABEL = "Magic link"
    const val AUTH_METHOD_MAGIC_LINK_DESC =
        "One-time sign-in link delivered by email. Single-use, expires after the configured window."
    const val AUTH_METHOD_EMAIL_OTP_LABEL = "Email code"
    const val AUTH_METHOD_EMAIL_OTP_DESC =
        "6-digit code delivered by email. The user enters it after their email address."
    const val AUTH_METHOD_SOCIAL_GOOGLE_LABEL = "Google"
    const val AUTH_METHOD_SOCIAL_GITHUB_LABEL = "GitHub"

    // Auth Methods grid (v1.20.1)
    const val AUTH_METHODS_TABLE_HEADING = "Sign-in methods"
    const val REQUIREMENT_SMTP_REQUIRED = "SMTP required"
    const val REQUIREMENT_SMTP_LINK = "Set up SMTP"
    const val REQUIREMENT_OAUTH_CREDENTIALS_REQUIRED = "OAuth credentials required"
    const val AUTH_METHODS_PASSWORD_OFF_WARNING =
        "Disabling passwords requires ≥1 other method and configured SMTP for magic-link recovery."

    // Passkeys card (workspace security settings — v1.20)
    const val ADMIN_PASSKEYS_HEADING = "Passkeys"
    const val ADMIN_PASSKEYS_ENABLED_LABEL = "Passkey sign-in enabled"
    const val ADMIN_PASSKEYS_RESET_ALL_BUTTON = "Reset all passkeys"
    const val ADMIN_MFA_RESET_BUTTON = "Reset MFA"

    // Security rail nav labels (v1.20.1)
    const val ADMIN_NAV_SIGN_IN_METHODS = "Sign-in Methods"

    // Passkeys workspace page (v1.20.1)
    const val ADMIN_PASSKEYS_PAGE_TITLE = "Passkeys"
    const val ADMIN_PASSKEYS_ENROLLMENT_LABEL = "Passkey enrollment"
    const val ADMIN_PASSKEYS_ENROLLMENT_HINT = "users have enrolled at least one passkey"
    const val ADMIN_PASSKEYS_CONFIG_LABEL = "Passkey configuration"
    const val ADMIN_PASSKEYS_CONFIG_BODY =
        "Passkey sign-in is enabled or disabled from the Sign-in Methods page."
    const val ADMIN_PASSKEYS_OPEN_POLICY = "Open Sign-in Methods"

    // MFA overview page (v1.20.1)
    const val ADMIN_MFA_ALERT_NO_ENROLLED_TITLE = "No users have enrolled in MFA"
    const val ADMIN_MFA_ALERT_REQUIRED_DESC_PREFIX = "MFA policy is set to"
    const val ADMIN_MFA_ALERT_REQUIRED_DESC_SUFFIX =
        ". Users who have not enrolled cannot complete sign-in."
    const val ADMIN_MFA_ALERT_OPTIONAL_DESC =
        "MFA policy is Optional — no sign-in impact. Share the enrollment URL below to encourage " +
            "users to set up two-factor authentication."
    const val ADMIN_MFA_ALERT_REQUIRED_LINK = "Review policy"
    const val ADMIN_MFA_ALERT_OPTIONAL_LINK = "Change policy"
    const val ADMIN_MFA_TABLE_COL_USERNAME = "Username"
    const val ADMIN_MFA_TABLE_COL_FULL_NAME = "Full Name"
    const val ADMIN_MFA_TABLE_COL_EMAIL = "Email"
    const val ADMIN_MFA_TABLE_COL_STATUS = "MFA Status"
    const val ADMIN_MFA_TABLE_BADGE_ENROLLED = "Enrolled"
    const val ADMIN_MFA_TABLE_BADGE_NOT_ENROLLED = "Not enrolled"
    const val ADMIN_MFA_EMPTY_USERS_TITLE = "No users in this workspace"
    const val ADMIN_MFA_EMPTY_USERS_DESC = "Users will appear here once they are added."

    const val TOAST_BACKUP_EXPORTED = "Backup exported. Download started."
    const val TOAST_BACKUP_IMPORTED = "Workspace imported successfully."

    // -------------------------------------------------------------------------
    // Auth pages — shared chrome (page titles use {0} = workspace name)
    // -------------------------------------------------------------------------
    const val AUTH_PAGE_TITLE_LOGIN = "{0} | Sign In"
    const val AUTH_PAGE_TITLE_FORGOT = "{0} | Forgot Password"
    const val AUTH_PAGE_TITLE_RESET = "{0} | Reset Password"
    const val AUTH_PAGE_TITLE_INVITE = "{0} | Accept Invitation"
    const val AUTH_PAGE_TITLE_MFA = "{0} | Two-Factor Authentication"
    const val AUTH_PAGE_TITLE_REGISTER = "{0} | Create Account"
    const val AUTH_PAGE_TITLE_MAGIC_LINK = "{0} | Sign in with email"
    const val AUTH_PAGE_TITLE_EMAIL_OTP = "{0} | Sign in with email code"
    const val AUTH_PAGE_TITLE_FORCE_CHANGE = "{0} | Change Password"
    const val AUTH_PAGE_TITLE_VERIFY_EMAIL = "{0} | Email Verification"
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
    const val LOGIN_EMAIL_OTP_LINK = "Sign in with an email code instead"
    const val LOGIN_NO_ACCOUNT = "Don't have an account? "
    const val LOGIN_CREATE_ONE = "Create one"
    const val LOGIN_OR_CONTINUE_WITH = "or continue with"
    const val LOGIN_CONTINUE_GOOGLE = "Continue with Google"
    const val LOGIN_CONTINUE_GITHUB = "Continue with GitHub"
    const val LOGIN_PROVIDER_GOOGLE = "Google"
    const val LOGIN_PROVIDER_GITHUB = "GitHub"
    const val AUTH_LOGIN_PASSKEY_BUTTON = "Sign in with a passkey"
    const val AUTH_LOGIN_MAGIC_LINK_BUTTON = "Sign in with a magic link"

    // Login page — passwordless mode (when tenant disables password sign-in)
    const val LOGIN_PASSWORDLESS_SUBTITLE = "Sign in with an email link"
    const val LOGIN_PASSWORDLESS_EMAIL_LABEL = "Email address"
    const val LOGIN_PASSWORDLESS_EMAIL_PLACEHOLDER = "you@example.com"
    const val LOGIN_PASSWORDLESS_SUBMIT = "Send sign-in link"

    // Registration page
    const val REGISTER_TITLE = "Create account"
    const val REGISTER_SUBTITLE = "Fill in your details to get started"
    const val REGISTER_PASSWORDLESS_SUBTITLE = "Fill in your details — we'll email you a sign-in link to finish."
    const val REGISTER_FULL_NAME = "Full Name"
    const val REGISTER_FULL_NAME_PLACEHOLDER = "Your full name"
    const val REGISTER_EMAIL = "Email Address"
    const val REGISTER_EMAIL_PLACEHOLDER = "you@example.com"
    const val REGISTER_USERNAME = "Username"
    const val REGISTER_USERNAME_PLACEHOLDER = "Choose a username"
    const val REGISTER_SUBMIT = "Create Account"
    const val REGISTER_HAS_ACCOUNT = "Already have an account? "
    const val REGISTER_SIGN_IN = "Sign in"
    const val REGISTER_OR_SIGN_UP_WITH = "or sign up with"

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

    // Magic-link request page
    const val MAGIC_LINK_TITLE = "Sign in with email"
    const val MAGIC_LINK_SUBTITLE_FORM =
        "Enter your email address and we'll send you a one-time link to sign in. " +
            "No password needed."
    const val MAGIC_LINK_SUBTITLE_SENT =
        "If an account exists for that email address, you'll receive a sign-in link " +
            "shortly. The link expires in 15 minutes."
    const val MAGIC_LINK_EMAIL_LABEL = "Email address"
    const val MAGIC_LINK_EMAIL_PLACEHOLDER = "you@example.com"
    const val MAGIC_LINK_SUBMIT = "Send sign-in link"

    // Magic-link error page
    const val MAGIC_LINK_ERROR_TITLE = "Sign-in link unavailable"
    const val MAGIC_LINK_REQUEST_NEW = "Request a new link"

    // Email-OTP login pages (v1.13.0 — hosted browser flow)
    const val EMAIL_OTP_TITLE = "Sign in with email code"
    const val EMAIL_OTP_SUBTITLE_FORM =
        "Enter your email address and we'll send you a 6-digit code to sign in."
    const val EMAIL_OTP_EMAIL_LABEL = "Email address"
    const val EMAIL_OTP_EMAIL_PLACEHOLDER = "you@example.com"
    const val EMAIL_OTP_SEND_SUBMIT = "Send code"
    const val EMAIL_OTP_VERIFY_TITLE = "Enter your sign-in code"
    const val EMAIL_OTP_VERIFY_SUBTITLE =
        "Check your inbox for a 6-digit code. It expires in 10 minutes."
    const val EMAIL_OTP_VERIFY_RESENT =
        "A new code has been sent. The previous code is no longer valid."
    const val EMAIL_OTP_CODE_LABEL = "Sign-in code"
    const val EMAIL_OTP_CODE_PLACEHOLDER = "000000"
    const val EMAIL_OTP_VERIFY_SUBMIT = "Sign in"
    const val EMAIL_OTP_RESEND = "Resend code"
    const val EMAIL_OTP_USE_DIFFERENT_EMAIL = "Use a different email"
    const val EMAIL_OTP_ERROR_TITLE = "Sign-in code unavailable"

    // Force change password page
    const val FORCE_CHANGE_TITLE = "Change your password"
    const val FORCE_CHANGE_SUBTITLE_LINE1 = "An administrator has required you to change your password. "
    const val FORCE_CHANGE_SUBTITLE_LINE2 = "Pick a new one to continue."
    const val FORCE_CHANGE_SUCCESS =
        "Your password has been changed. All existing sessions have been signed out."
    const val FORCE_CHANGE_GO_SIGN_IN = "Go to sign in"
    const val FORCE_CHANGE_SUBMIT = "Change Password"

    // Verify email page
    const val VERIFY_EMAIL_TITLE = "Email verification"
    const val VERIFY_EMAIL_PROBLEM = "There was a problem with your verification link."
    const val VERIFY_EMAIL_SIGN_IN = "Sign in to your account"

    // Social registration page
    const val SOCIAL_REG_TITLE = "One last step"
    const val SOCIAL_REG_SUBTITLE = "You're signing in with {0}. Choose a username to complete your account."
    const val SOCIAL_REG_EMAIL_FROM = "Email (from {0})"
    const val SOCIAL_REG_FULL_NAME_PLACEHOLDER = "Your display name"
    const val SOCIAL_REG_USERNAME_PLACEHOLDER = "letters, numbers, underscores"
    const val SOCIAL_REG_SUBMIT = "Create account"

    // MFA challenge page
    const val MFA_TAGLINE = "Two-factor authentication"
    const val MFA_VERIFY_IDENTITY = "Verify your identity"
    const val MFA_SUBTITLE = "Enter the 6-digit code from your authenticator app, or a recovery code."
    const val MFA_CODE_LABEL = "Authentication code"
    const val MFA_CODE_PLACEHOLDER = "Enter 6-digit code or recovery code"
    const val MFA_VERIFY_BUTTON = "Verify"

    // Transactional emails — see SmtpEmailAdapter. {0} is workspace name unless otherwise noted.
    // Subjects
    const val EMAIL_SUBJECT_VERIFY = "Verify your email address — {0}"
    const val EMAIL_SUBJECT_PASSWORD_RESET = "Reset your password — {0}"
    const val EMAIL_SUBJECT_ACCOUNT_LOCKED = "Your account has been locked — {0}"
    const val EMAIL_SUBJECT_PASSWORD_CHANGED = "Your password has been changed — {0}"
    const val EMAIL_SUBJECT_TEST = "KotAuth SMTP Test — {0}"
    const val EMAIL_SUBJECT_INVITE = "You've been invited to join {0}"
    const val EMAIL_SUBJECT_MAGIC_LINK = "Your sign-in link for {0}"
    const val EMAIL_SUBJECT_OTP = "Your sign-in code for {0}"

    // Greeting shared across emails. {0} = recipient name (HTML-escaped by builder).
    const val EMAIL_GREETING = "Hi {0},"

    // Headings — short titles rendered inside the email
    const val EMAIL_HEADING_VERIFY = "Verify your email address"
    const val EMAIL_HEADING_PASSWORD_RESET = "Reset your password"
    const val EMAIL_HEADING_ACCOUNT_LOCKED = "Your account has been locked"
    const val EMAIL_HEADING_PASSWORD_CHANGED = "Your password has been changed"
    const val EMAIL_HEADING_TEST = "SMTP Configuration Test"
    const val EMAIL_HEADING_INVITE = "You’ve been invited"
    const val EMAIL_HEADING_MAGIC_LINK = "Sign in to {0}"
    const val EMAIL_HEADING_OTP = "Your sign-in code"

    // Bodies — sentence(s) shown below heading. Placeholders documented per key.
    const val EMAIL_BODY_VERIFY = "Click the button below to verify your email address. This link expires in 24 hours."
    const val EMAIL_BODY_PASSWORD_RESET =
        "We received a request to reset your password. Click the button below to choose a new one. " +
            "This link expires in 1 hour."

    // {0} = workspace name, {1} = lockout duration ("15 minutes")
    const val EMAIL_BODY_ACCOUNT_LOCKED =
        "We temporarily locked your {0} account after several failed sign-in attempts. " +
            "Your account will automatically unlock in {1}. " +
            "If you'd like to regain access sooner, or if you don't recognize this activity, " +
            "you can reset your password now."

    // {0} = workspace name
    const val EMAIL_BODY_PASSWORD_CHANGED =
        "Your {0} password was successfully changed. " +
            "If you made this change, no action is needed. " +
            "If you did not make this change, reset your password immediately."

    // {0} = login URL — substituted by builder so HTML / text variants can format the URL differently.
    const val EMAIL_BODY_PASSWORD_CHANGED_LOGIN_HINT =
        "Sign in at {0} and use the Forgot password link."

    // {0} = workspace name
    const val EMAIL_BODY_TEST =
        "This email confirms that SMTP is correctly configured for {0}. " +
            "Email delivery (verification, password reset, notifications) is operational."

    // {0} = workspace name
    const val EMAIL_BODY_INVITE =
        "You’ve been added to {0}. " +
            "Click the button below to set your password and activate your account. " +
            "This link expires in 72 hours."

    const val EMAIL_BODY_MAGIC_LINK =
        "Click the button below to sign in. This link expires in 15 minutes and can only be used once."

    // {0} = code expiry in minutes
    const val EMAIL_BODY_OTP =
        "Use this {0}-minute code to finish signing in. Don’t share it with anyone."

    // CTAs — button labels
    const val EMAIL_CTA_VERIFY = "Verify email address"
    const val EMAIL_CTA_PASSWORD_RESET = "Reset password"
    const val EMAIL_CTA_INVITE = "Set your password"
    const val EMAIL_CTA_MAGIC_LINK = "Sign in"

    // Footers
    const val EMAIL_FOOTER_VERIFY = "If you did not create an account, you can safely ignore this email."
    const val EMAIL_FOOTER_PASSWORD_RESET = "If you did not request a password reset, you can safely ignore this email."
    const val EMAIL_FOOTER_PASSWORD_CHANGED =
        "For security, all active sessions were signed out when your password was changed."
    const val EMAIL_FOOTER_ACCOUNT_LOCKED =
        "If you made these sign-in attempts, you can safely ignore this email — your account will unlock automatically."
    const val EMAIL_FOOTER_TEST = "Sent by KotAuth to verify SMTP configuration."
    const val EMAIL_FOOTER_INVITE =
        "If you weren’t expecting this, you can safely ignore this email. " +
            "No account will be activated without clicking the link above."
    const val EMAIL_FOOTER_MAGIC_LINK = "If you did not request this link, you can safely ignore this email."
    const val EMAIL_FOOTER_OTP = "If you didn’t request a code, you can safely ignore this email."

    const val BRAND_IDENTITY_HEADING = "Brand Identity"
    const val VISUAL_THEME_HEADING = "Visual Theme"

    const val POST_MAGIC_LINK_TITLE = "Sign in complete"
    const val POST_MAGIC_LINK_INTRO =
        "Adding a passkey lets you sign in faster next time without a magic link."
    const val POST_MAGIC_LINK_ENROLL_CTA = "Enroll a passkey on this device"
    const val POST_MAGIC_LINK_SKIP_CTA = "Continue without a passkey"
    const val POST_MAGIC_LINK_PASSKEY_DEFAULT_NAME = "This device"

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
