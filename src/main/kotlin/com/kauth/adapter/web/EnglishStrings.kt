package com.kauth.adapter.web

import com.kauth.domain.model.Group
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.service.childGroupsBlockDeleteMessage
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
    const val TOAST_IDP_DELETED = "Identity provider deleted."
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

    // Sign-in errors
    const val SIGN_IN_WRONG_WORKSPACE =
        "This sign-in does not belong to this workspace. Please sign in again."

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
        "Save these codes now. They won't be shown again after you leave this page."
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
    const val IMPERSONATION_ACTIVE_HEADING = "Impersonation in progress"
    const val IMPERSONATION_ACTIVE_USER = "Signed in as"
    const val IMPERSONATION_ACTIVE_SINCE = "Started"
    const val IMPERSONATION_STOP_BUTTON = "End impersonation"
    const val IMPERSONATION_STOP_CONFIRM =
        "End this impersonation session? You return to your own admin session, which was never " +
            "signed out."

    /**
     * Names the session that is about to be ended.
     *
     * The route has always revoked a prior impersonation before starting a new one, so this is a
     * description of what happens rather than a question about what should.
     */
    fun impersonateReplaceConfirm(currentTarget: String): String =
        "You are already impersonating $currentTarget. Starting a new session ends that one. Continue?"

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
        "Register the APIs (resource servers) that your clients request audience-targeted tokens for. " +
            "Each API has an identifier (the `aud` claim) that resource servers use to validate incoming tokens."
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

    // The empty case here is permissive, and the allowed-domain list on an identity provider is
    // restrictive. Two empty lists, opposite meanings: both have to say which one they are, and
    // neither should be softened into the other's voice.
    const val RESOURCE_SERVER_SCOPES_HINT =
        "Tokens issued for this API are narrowed to the scopes in this list. An empty list " +
            "applies no narrowing: a token keeps every scope the client asked for."
    const val RESOURCE_SERVER_SCOPES_NONE =
        "No scopes listed, so tokens for this API are not narrowed. Every scope a client asks " +
            "for is issued."

    // Grant types
    const val GRANT_TYPES_LABEL = "Grant Types"
    const val GRANT_TYPES_HINT =
        "Which OAuth2 grants this application may use. Only the selected grants will be accepted at the " +
            "token endpoint."
    const val GRANT_AUTHORIZATION_CODE_HINT = "Browser sign-in. Requires at least one redirect URI."
    const val GRANT_CLIENT_CREDENTIALS_HINT =
        "Machine-to-machine. No user and no redirect URI. Confidential applications only."
    const val GRANT_REFRESH_TOKEN_HINT = "Allows exchanging a refresh token for a new access token."
    const val AUTHORIZED_APIS_CARD_TITLE = "Authorized APIs"
    const val AUTHORIZED_APIS_CARD_EMPTY_TITLE = "No APIs authorized"
    const val AUTHORIZED_APIS_CARD_EMPTY =
        "Token requests targeting an API will be refused until at least one API is authorized."
    const val AUTHORIZED_APIS_CARD_ACTION = "Manage Authorized APIs"

    // Application forms — token audience
    const val APPLICATION_AUDIENCE_LABEL = "Token Audience"
    const val APPLICATION_AUDIENCE_PLACEHOLDER = "https://api.example.com"
    const val APPLICATION_AUDIENCE_HINT_PREFIX = "Sets the "
    const val APPLICATION_AUDIENCE_HINT_CLAIM = "aud"
    const val APPLICATION_AUDIENCE_HINT_SUFFIX =
        " claim in issued JWTs. Leave blank to use the client ID as the audience."

    // Tenant backup / restore (v1.9.0)
    const val BACKUP_NAV_LABEL = "Backup"
    const val BACKUP_PAGE_TITLE = "Backup workspace"
    const val BACKUP_PAGE_SUBTITLE =
        "Export this workspace to an encrypted file. Use the file to restore the workspace " +
            "elsewhere, or to clone it into a staging environment."
    const val BACKUP_PASSPHRASE_LABEL = "Backup passphrase"
    const val BACKUP_PASSPHRASE_HINT =
        "16+ characters. The exported file is unreadable without it. " +
            "Kotauth never stores this passphrase, so keep it somewhere safe."
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
        "OAuth client secrets: regenerate per application after import."
    const val IMPORT_RECOVERY_SOCIAL_SECRETS =
        "Social provider client secrets: re-enter in Settings › Identity Providers."
    const val IMPORT_RECOVERY_SMTP_PASSWORD =
        "SMTP password: reconfigure in Settings › SMTP."
    const val IMPORT_RECOVERY_MFA_SEEDS =
        "MFA TOTP seeds and recovery codes: users must re-enroll after import."
    const val IMPORT_RECOVERY_SESSIONS =
        "Active sessions, authorization codes, and magic-link tokens were never exported."

    const val IMPORT_PAGE_TITLE = "Import workspace from backup"
    const val IMPORT_PAGE_SUBTITLE =
        "Restore a previously exported workspace as a new tenant on this deployment."
    const val IMPORT_FILE_LABEL = "Backup file (.json.enc)"
    const val IMPORT_NEW_SLUG_LABEL = "New workspace slug"
    const val IMPORT_NEW_SLUG_HINT =
        "Lowercase letters, digits, and hyphens. 2 to 50 characters. Must not match an existing workspace."
    const val IMPORT_PASSPHRASE_LABEL = "Backup passphrase"
    const val IMPORT_PASSPHRASE_HINT = "The passphrase used at export time."
    const val IMPORT_SUBMIT_BUTTON = "Import workspace"
    const val IMPORT_LINK_FROM_LIST = "Import from backup"
    const val IMPORT_LINK_FROM_CREATE = "Restoring from a backup? Import instead."

    /**
     * The console's shared vocabulary: column headers, action labels and state words that appear
     * on more than one page.
     *
     * Each was written out at every site, so the same word drifted between them — "Danger zone"
     * stood at four sites and "Active" at five, each free to be edited alone. Words that merely
     * coincide are kept apart: the registration state "Open" is not the "Open" on a row action,
     * and collapsing them would tie two unrelated strings together.
     */
    const val COL_NAME = "Name"
    const val COL_DESCRIPTION = "Description"
    const val COL_STATUS = "Status"
    const val COL_USERNAME = "Username"
    const val COL_EMAIL = "Email"
    const val COL_CREATED = "Created"
    const val COL_EXPIRES = "Expires"
    const val COL_EVENT = "Event"
    const val COL_ROLES = "Roles"

    /** The row action that opens a record. Named for what the reader gets, not for the gesture. */
    const val ACTION_VIEW_DETAIL = "View detail"

    const val DANGER_ZONE_HEADING = "Danger zone"

    // Sign-in methods table columns.
    const val AUTH_METHODS_TABLE_COL_METHOD = "Method"
    const val AUTH_METHODS_TABLE_COL_ENABLED = "Enabled"

    const val AUTH_METHODS_EMAIL_OTP_SMTP_WARN =
        "SMTP is not configured for this workspace. OTP emails will fail silently until " +
            "you configure SMTP under workspace settings."

    // Sign-in Methods grid rows (v1.20.1 — SecurityMethodsService)
    const val SIGN_IN_METHODS_PAGE_SUB =
        "Which methods this workspace supports. Anyone enrolled in MFA is still challenged, " +
            "whichever method they use."
    const val AUTH_METHOD_PASSWORD_LABEL = "Password"
    const val AUTH_METHOD_PASSKEY_LABEL = "Passkey"
    const val AUTH_METHOD_MAGIC_LINK_LABEL = "Magic link"
    const val AUTH_METHOD_MAGIC_LINK_DESC = "A sign-in link sent by email. It works once."
    const val AUTH_METHOD_EMAIL_OTP_LABEL = "Email code"
    const val AUTH_METHOD_EMAIL_OTP_DESC = "A 6-digit code sent by email, entered after the address."
    const val AUTH_METHOD_SOCIAL_GOOGLE_LABEL = "Google"
    const val AUTH_METHOD_SOCIAL_GITHUB_LABEL = "GitHub"

    /**
     * The one aggregate row that stands for every brokered identity provider.
     *
     * A provider key is an open string, so there is no MethodKey to give each one a row of its
     * own — and a grid that grew a row per provider would stop being the sign-in method grid.
     */
    const val AUTH_METHOD_EXTERNAL_IDP_LABEL = "External identity providers"
    const val AUTH_METHOD_EXTERNAL_IDP_DESC =
        "Each provider is switched on and off where it is configured."
    const val AUTH_METHODS_EXTERNAL_IDP_MANAGE = "Manage identity providers"
    const val AUTH_METHODS_EXTERNAL_IDP_NONE_ENABLED = "None enabled"

    // Auth Methods grid (v1.20.1)
    const val REQUIREMENT_SMTP_REQUIRED = "SMTP required"
    const val REQUIREMENT_SMTP_LINK = "Set up SMTP"
    const val REQUIREMENT_OAUTH_CREDENTIALS_REQUIRED = "OAuth credentials required"
    const val REQUIREMENT_OAUTH_LINK = "Set up credentials"
    const val AUTH_METHODS_PASSWORD_OFF_WARNING =
        "Disabling passwords requires ≥1 other method and configured SMTP for magic-link recovery."

    // Passkeys card (workspace security settings — v1.20)
    const val ADMIN_PASSKEYS_HEADING = "Passkeys"
    const val ADMIN_PASSKEYS_ENABLED_LABEL = "Passkey sign-in enabled"
    const val ADMIN_PASSKEYS_RESET_ALL_BUTTON = "Reset all passkeys"
    const val ADMIN_MFA_RESET_BUTTON = "Reset MFA"

    // Security rail nav labels (v1.20.1)
    const val ADMIN_NAV_SIGN_IN_METHODS = "Sign-in Methods"

    // Passkeys workspace page (v1.20.1 rewrite)
    // Admin — Security Policy page section labels
    const val ADMIN_SECURITY_PASSWORD_POLICY_SECTION = "Password Policy"
    const val ADMIN_SECURITY_PASSWORD_REQUIREMENTS_SECTION = "Password Requirements"

    const val ADMIN_PASSKEYS_PAGE_TITLE = "Passkeys"
    const val ADMIN_PASSKEYS_ENROLLMENT_LABEL = "Passkey enrollment"
    const val ADMIN_PASSKEYS_ENROLLMENT_HINT = "users have enrolled at least one passkey"
    const val ADMIN_PASSKEYS_CONFIG_LABEL = "Passkey configuration"
    const val ADMIN_PASSKEYS_CONFIG_BODY =
        "Passkey sign-in is enabled or disabled from the Sign-in Methods page."
    const val ADMIN_PASSKEYS_OPEN_POLICY = "Open Sign-in Methods"

    // Passkeys admin page — alerts
    const val ADMIN_PASSKEYS_ALERT_DISABLED_NO_USERS_TITLE = "Passkey sign-in is disabled"
    const val ADMIN_PASSKEYS_ALERT_DISABLED_NO_USERS_DESC =
        "Enable it on Sign-in Methods to let users enroll."
    const val ADMIN_PASSKEYS_ALERT_ENABLED_NO_USERS_TITLE = "No users have enrolled a passkey yet"
    const val ADMIN_PASSKEYS_ALERT_ENABLED_NO_USERS_DESC =
        "Sharing the enrollment URL below is the fastest way to get started."
    const val ADMIN_PASSKEYS_ALERT_DISABLED_HAS_USERS_TITLE = "Passkey sign-in is currently disabled"
    const val ADMIN_PASSKEYS_ALERT_SIGN_IN_METHODS_LINK = "Open Sign-in Methods"

    // Passkeys admin page — enrollment URL card
    const val ADMIN_PASSKEYS_ENROLLMENT_URL_LABEL = "Self-service enrollment URL"
    const val ADMIN_PASSKEYS_ENROLLMENT_URL_DESC =
        "Share this URL with users to let them register a passkey on their device. " +
            "The link requires them to be signed in."

    // Passkeys admin page — users table
    const val ADMIN_PASSKEYS_TABLE_COL_STATUS = "Passkey Status"
    const val ADMIN_PASSKEYS_TABLE_BADGE_ENROLLED = "Enrolled"
    const val ADMIN_PASSKEYS_TABLE_BADGE_NOT_ENROLLED = "Not enrolled"
    const val ADMIN_PASSKEYS_EMPTY_USERS_TITLE = "No users in this workspace"
    const val ADMIN_PASSKEYS_EMPTY_USERS_DESC = "Users will appear here once they are added."

    // Sign-in Methods — footer note
    const val ADMIN_METHODS_MORE_SIGN_IN_OPTIONS = "Add more sign-in options via Identity Providers"

    // MFA overview page (v1.20.1)
    const val ADMIN_MFA_ALERT_NO_ENROLLED_TITLE = "No users have enrolled in MFA"
    const val ADMIN_MFA_ALERT_REQUIRED_DESC_PREFIX = "MFA policy is set to"
    const val ADMIN_MFA_ALERT_REQUIRED_DESC_SUFFIX =
        ". Users who have not enrolled cannot complete sign-in."
    const val ADMIN_MFA_ALERT_OPTIONAL_DESC =
        "MFA policy is Optional, so there is no sign-in impact. Share the enrollment URL below to " +
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
    const val LOGIN_REGISTRATION_SUCCESS = "Account created successfully. Please sign in."
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
    const val LOGIN_CONTINUE_GENERIC = "Continue with {provider}"
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
    const val REGISTER_PASSWORDLESS_SUBTITLE = "Fill in your details and we'll email you a sign-in link to finish."
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

    // A brokered sign-in the workspace refused after the provider had already signed the person in
    const val AUTH_PAGE_TITLE_ACCESS_REFUSED = "{0} | Access not granted"
    const val JIT_REFUSED_TITLE = "Signed in, but not allowed in"
    const val JIT_REFUSED_AUTHENTICATED =
        "{0} signed you in successfully. Nothing is wrong with your password or your {0} account. " +
            "{1} has not granted this account access."
    const val JIT_REFUSED_EMAIL_NOT_VERIFIED_HEADING = "Your email address is not verified"
    const val JIT_REFUSED_EMAIL_NOT_VERIFIED_BODY =
        "{1} only creates accounts for addresses the provider has confirmed, and {0} has not " +
            "confirmed yours. Verify your email address with {0}, then sign in again."
    const val JIT_REFUSED_DOMAIN_NOT_ALLOWED_HEADING = "Your email domain is not on the allowed list"
    const val JIT_REFUSED_DOMAIN_NOT_ALLOWED_BODY =
        "{1} creates accounts only for people whose email address is on its list of approved " +
            "domains, and yours is not on it. Signing in again will not change this. Ask an " +
            "administrator of {1} to add your domain."
    const val JIT_REFUSED_USERNAME_CONFLICT_HEADING = "An account here already uses your email address"
    const val JIT_REFUSED_USERNAME_CONFLICT_BODY =
        "{1} already has an account whose sign-in name is your email address, and it is not this " +
            "one. Signing in again will not change this. Ask an administrator of {1} to sort the " +
            "two records out."
    const val JIT_REFUSED_REFERENCE = "Quote this reference to an administrator: {0}"

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
    const val EMAIL_SUBJECT_VERIFY = "Verify your email address ({0})"
    const val EMAIL_SUBJECT_PASSWORD_RESET = "Reset your password ({0})"
    const val EMAIL_SUBJECT_ACCOUNT_LOCKED = "Your account has been locked ({0})"
    const val EMAIL_SUBJECT_PASSWORD_CHANGED = "Your password has been changed ({0})"
    const val EMAIL_SUBJECT_TEST = "KotAuth SMTP Test ({0})"
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
        "If you made these sign-in attempts, you can safely ignore this email. Your account will unlock automatically."
    const val EMAIL_FOOTER_TEST = "Sent by KotAuth to verify SMTP configuration."
    const val EMAIL_FOOTER_INVITE =
        "If you weren’t expecting this, you can safely ignore this email. " +
            "No account will be activated without clicking the link above."
    const val EMAIL_FOOTER_MAGIC_LINK = "If you did not request this link, you can safely ignore this email."
    const val EMAIL_FOOTER_OTP = "If you didn’t request a code, you can safely ignore this email."

    const val BRAND_IDENTITY_HEADING = "Brand Identity"
    const val VISUAL_THEME_HEADING = "Visual Theme"

    const val BRANDING_LOGIN_LAYOUT_TITLE = "Login layout"
    const val BRANDING_LOGIN_LAYOUT_DESC =
        "Choose how the auth pages (login, register, MFA) are structured. Split adds a branded left panel."
    const val BRANDING_LOGIN_LAYOUT_FIELD = "Layout"
    const val BRANDING_LOGIN_TAGLINE_FIELD = "Tagline (split layout)"
    const val BRANDING_LOGIN_TAGLINE_HINT =
        "Shown on the left panel when using the Split layout. Falls back to the workspace name if empty."
    const val BRANDING_LOGIN_BG_FIELD = "Background image URL (split layout)"
    const val BRANDING_LOGIN_BG_HINT =
        "Optional image for the left panel. Must be an https URL. If empty, the panel uses the accent color."

    const val POST_MAGIC_LINK_TITLE = "Sign in complete"
    const val POST_MAGIC_LINK_INTRO =
        "Adding a passkey lets you sign in faster next time without a magic link."
    const val POST_MAGIC_LINK_ENROLL_CTA = "Enroll a passkey on this device"
    const val POST_MAGIC_LINK_SKIP_CTA = "Continue without a passkey"
    const val POST_MAGIC_LINK_PASSKEY_DEFAULT_NAME = "This device"

    // Admin — groups
    const val GROUP_DELETE = "Delete"
    const val GROUP_DELETE_BLOCKED_TITLE = "Subgroups must be resolved first"

    fun groupDeleteConfirm(groupName: String) = "Delete group $groupName?"

    /**
     * One wording for one rule. The service's copy is the one that reaches API and SCIM clients,
     * so it is the source; this re-export keeps a view author finding the string here rather than
     * writing a second, subtly different sentence for the same refusal.
     */
    fun groupDeleteBlockedBySubgroups(
        groupName: String,
        subgroups: List<Group>,
    ) = childGroupsBlockDeleteMessage(groupName, subgroups)

    // Admin — SCIM provisioning
    const val SCIM_NAV_LABEL = "Provisioning"
    const val SCIM_PAGE_TITLE = "Provisioning"
    const val SCIM_PAGE_SUBTITLE =
        "Let an identity provider create, update, and deactivate this workspace's users and groups over SCIM 2.0."

    const val SCIM_ENDPOINT_HEADING = "Endpoint"
    const val SCIM_ENDPOINT_LABEL = "Base URL"
    const val SCIM_ENDPOINT_HINT =
        "The same for every provisioning client in this workspace. Paste it wherever your identity provider " +
            "asks for the SCIM base or tenant URL."

    /**
     * The one sentence that follows a freshly created secret.
     *
     * It was written four different ways on four surfaces, so the promise the product makes
     * about a value it will never show again varied by page.
     */
    const val SECRET_SHOWN_ONCE = "Copy it now. You will not see it again."

    /** The same policy stated as a standing fact rather than as a banner. */
    const val API_KEY_SHOWN_ONCE = "A key value is shown once, when you create it."

    const val SCIM_TOKEN_HEADING = "Token"
    const val SCIM_TOKEN_HINT =
        "Provisioning authenticates with an API key holding the scim scope, sent as a bearer token. " +
            API_KEY_SHOWN_ONCE
    const val SCIM_TOKEN_MANAGE_CTA = "Manage API keys"
    const val SCIM_TOKEN_CREATE_CTA = "Create a provisioning key"
    const val SCIM_KEYS_EMPTY_TITLE = "No provisioning key yet"
    const val SCIM_KEYS_EMPTY_BODY =
        "Create an API key with the scim scope, then paste it into your identity provider as the secret token."
    const val SCIM_KEYS_COL_NAME = "Key"
    const val SCIM_KEYS_COL_DIALECT = "Dialect"
    const val SCIM_KEYS_COL_LAST_USED = "Last API use"
    const val SCIM_KEYS_COL_STATE = "State"
    const val SCIM_KEYS_NEVER_USED = "Never"

    const val SCIM_STATUS_HEADING = "Status"

    /**
     * Deliberately not a green "connected" badge. Nothing in the audit log records an individual
     * SCIM request or the key that made it, so any timestamp shown here would be inferred rather
     * than observed — and an operator trusting a wrong "connected" is worse off than one told
     * plainly that the answer is not available yet.
     */
    const val SCIM_STATUS_UNKNOWN =
        "Not verified. KotAuth does not yet record individual SCIM requests in the audit log, so a successful " +
            "connection cannot be confirmed from here. Check your identity provider's own provisioning log."
    const val SCIM_STATUS_NO_KEY =
        "Not connected. This workspace has no API key holding the scim scope, so every provisioning request " +
            "is rejected."

    /**
     * Distinct from [SCIM_STATUS_NO_KEY]: the table right below this row lists the revoked keys
     * with their badge, so telling the operator there is no such key contradicts the screen.
     */
    const val SCIM_STATUS_KEYS_REVOKED =
        "Not connected. Every API key holding the scim scope in this workspace is revoked, so every " +
            "provisioning request is rejected. Create a new key to reconnect."
    const val SCIM_STATUS_LAST_USE_HINT =
        "Last API use counts any request made with the key, not only provisioning requests."

    const val SCIM_BEHAVIOUR_HEADING = "What provisioning does"
    const val SCIM_DEPROVISION_HEADING = "Deprovisioning"
    const val SCIM_DELETE_DEACTIVATES =
        "DELETE deactivates a user instead of deleting it. The account stays in the directory, disabled, " +
            "so audit history and group membership survive a deprovision."
    const val SCIM_BEHAVIOUR_GROUPS =
        "Groups map to KotAuth groups. Member pushes carry user ids; a member the workspace does not have " +
            "is rejected rather than created."

    /**
     * The counterpart to [SCIM_DELETE_DEACTIVATES], and the reason it is spelled out: the notice
     * above it explains that deleting a user is reversible, which reads as a promise about DELETE
     * in general unless the group case says otherwise.
     */
    const val SCIM_DELETE_GROUP_PERMANENT =
        "DELETE on a group is permanent. Unlike a user, it is removed outright, along with its " +
            "memberships and role grants. A group that still has subgroups is refused rather than deleted."

    const val SCIM_NOTES_HEADING = "Identity provider notes"
    const val SCIM_NOTES_INTRO =
        "Connectors differ in what they ask for and what they put on the wire. Pick the matching dialect when " +
            "you create the key. It is read from the key, never guessed from a request header."

    const val SCIM_DIALECT_FIELD_LABEL = "SCIM dialect"
    const val SCIM_DIALECT_FIELD_HINT =
        "Applies only to keys holding the scim scope. Leave it on the default unless your identity provider " +
            "is listed."

    const val SCIM_DIALECT_SAVE_CTA = "Save"
    const val SCIM_DIALECT_SAVED_TOAST = "Dialect updated. The next provisioning request uses it."

    /**
     * A bootstrapped key's dialect is the entry's optional `scimDialect` field, re-asserted on every
     * restart, so editing it here would last only until the next one. The row points at the field
     * that does own it rather than leaving the operator without a way to change it.
     */
    const val SCIM_DIALECT_ENV_MANAGED = "Env-managed"
    const val SCIM_DIALECT_ENV_MANAGED_HINT =
        "Managed via KAUTH_BOOTSTRAP_API_KEYS. Set the entry's \"scimDialect\" field there; every " +
            "restart re-applies it."
    const val SCIM_DIALECT_ENV_MANAGED_REFUSAL =
        "Bootstrapped keys keep the dialect set by KAUTH_BOOTSTRAP_API_KEYS. Set the entry's " +
            "\"scimDialect\" field there instead."

    /**
     * A submitted id outside the registry means a stale or tampered form, not a new provider: the
     * selector only ever offers registered ids, so the submission is refused rather than quietly
     * saved as something else.
     */
    const val SCIM_DIALECT_UNKNOWN_REFUSAL =
        "That SCIM dialect is not one this version offers. Reload the page and pick a dialect from the list."

    const val SCIM_DIALECT_RFC_LABEL = "Standard SCIM 2.0 (RFC 7644)"
    const val SCIM_DIALECT_ENTRA_LABEL = "Microsoft Entra ID"
    const val SCIM_DIALECT_OKTA_LABEL = "Okta"

    val SCIM_DIALECT_RFC_NOTES =
        listOf(
            "The default, and a pass-through: payloads are parsed exactly as RFC 7644 defines them.",
            "Use it for any connector that follows the spec, and as the starting point for one you are unsure about.",
            "Configure the client with the base URL above and the API key as a bearer token.",
        )

    val SCIM_DIALECT_ENTRA_NOTES =
        listOf(
            "Enterprise application → Provisioning asks for two fields: Tenant URL and Secret Token.",
            "Tenant URL is the base URL above; Secret Token is the API key.",
            "Its patch requests send `active` as the strings \"True\" and \"False\"; this dialect reads them as " +
                "the booleans the spec requires, so a deprovision is not silently ignored.",
        )

    val SCIM_DIALECT_OKTA_NOTES =
        listOf(
            "Provisioning → Integration asks for the SCIM connector base URL, the unique identifier field for " +
                "users (use userName), and the authentication mode (HTTP Header, with the API key as the bearer " +
                "token).",
            "Enable Push New Users, Push Profile Updates, and Push Groups; deactivation arrives as a patch on " +
                "`active`.",
            "Its group pushes carry an advisory `display` name beside each member id. KotAuth stores none of " +
                "it under any dialect; this dialect drops it before the request is checked, so a `display` of " +
                "the wrong type is tolerated here instead of rejecting the whole push. The id is kept, and it " +
                "is the only part that identifies anyone.",
        )

    /**
     * Markers for a record an identity provider owns via SCIM.
     *
     * KotAuth stores that an `externalId` was set, never which provider set it, so the copy names
     * no vendor. The overwrite warning is the reason the badge exists: without it an operator edits
     * a name here, the next sync reverts it, and nothing on screen explains why.
     */
    const val SCIM_IDP_MANAGED_HEADING = "Identity provider"
    const val SCIM_IDP_MANAGED_BADGE = "IdP-managed"
    const val SCIM_IDP_MANAGED_MAY_BE_OVERWRITTEN =
        "This record is provisioned by an identity provider. Changes made here may be overwritten by the " +
            "identity provider on its next sync. Edit it there instead."
    const val SCIM_IDP_EXTERNAL_ID_LABEL = "External ID"
    const val SCIM_IDP_EXTERNAL_ID_HINT =
        "The identifier the identity provider uses for this record. It is how a sync finds the record again."

    /**
     * The badge on a record created by the broker on a first sign-in.
     *
     * The SCIM warning cannot be reused verbatim: no sync ever runs over a brokered account, so
     * "may be overwritten on its next sync" would send an operator looking for a sync that does
     * not exist. Same badge, different reason for it.
     */
    const val IDP_MANAGED_BROKERED_ORIGIN =
        "This account was created on a first sign-in through an identity provider. The person " +
            "signs in there and has no password here unless they set one."

    /**
     * The SCIM name parts on the user create and edit forms.
     *
     * They are stored beside the display name, never derived from it: the SCIM mapper keeps
     * `name.givenName` and `name.familyName` independent of `fullName`, so the hint has to stop an
     * operator expecting one to rewrite the other.
     */
    const val USER_GIVEN_NAME_LABEL = "Given name"
    const val USER_FAMILY_NAME_LABEL = "Family name"
    const val USER_NAME_PARTS_HINT =
        "Optional. Stored separately from the display name, so filling these in never rewrites Full Name."

    /** Group detail only: provisioning carries no roles, so the one thing the UI owns is safe to edit. */
    const val SCIM_IDP_MANAGED_ROLES_EDITABLE =
        "Role assignment is not provisioned and stays editable. Roles are assigned here and nowhere else, so a " +
            "sync never changes them."

    // ── Identity providers ──────────────────────────────────────────────────

    const val LINKED_IDENTITIES_HEADING = "Linked identities"
    const val LINKED_IDENTITIES_EMPTY =
        "This account signs in with its own credentials. Nothing is linked to an identity provider."
    const val LINKED_IDENTITIES_COL_PROVIDER = "Provider"
    const val LINKED_IDENTITIES_COL_ACCOUNT = "Account at the provider"
    const val LINKED_IDENTITIES_COL_SUBJECT = "Subject"
    const val LINKED_IDENTITIES_COL_LINKED = "Linked"
    const val LINKED_IDENTITIES_HINT =
        "A link is what lets this person sign in through that provider. Removing a provider leaves " +
            "the link in place but unusable."

    const val IDP_PAGE_TITLE = "Identity Providers"
    const val IDP_PAGE_SUB = "Configure SSO. Users can sign in with their existing accounts."
    const val IDP_CONFIGURED_HEADING = "Configured providers"
    const val IDP_ADD_HEADING = "Add a provider"
    const val IDP_NONE_TITLE = "No identity providers"
    const val IDP_NONE_DESC = "Users sign in with a password until you connect one."
    const val IDP_COL_PROVIDER = "Provider"
    const val IDP_COL_ISSUER = "Issuer"
    const val IDP_COL_JIT = "Just-in-time"
    const val IDP_COL_FAILURES = "Recent failures"
    const val IDP_COL_STATUS = "Status"
    const val IDP_CONFIGURE = "Configure"
    const val IDP_BUILT_IN = "Built-in adapter"
    const val IDP_JIT_OFF = "JIT off"
    const val IDP_STATUS_ENABLED = "Enabled"
    const val IDP_STATUS_DISABLED = "Disabled"
    const val IDP_STATUS_NOT_CONFIGURED = "Not configured"
    const val IDP_TILE_OAUTH2_HINT = "Built-in OAuth 2.0"

    // No vendor named here: a name in the UI reads as a provider this implementation has been
    // run against, and none has. See ADR-20.
    const val IDP_TILE_OIDC_HINT = "Any issuer with a discovery document"

    fun jitOnWithDomains(domainCount: Int): String =
        when (domainCount) {
            0 -> "JIT on \u00b7 no domains"
            1 -> "JIT on \u00b7 1 domain"
            else -> "JIT on \u00b7 $domainCount domains"
        }

    fun recentFailures(count: Int): String = if (count == 1) "1 recent failure" else "$count recent failures"

    // Identity provider diagnostics — the sign-in failures only a real sign-in can reveal
    const val IDP_FAILURES_HINT =
        "Sign-ins that reached this provider and did not end in a session. A callback URL the " +
            "provider does not recognise appears here and nowhere else. Testing the issuer's " +
            "discovery document cannot see it."
    const val IDP_FAILURES_EMPTY = "No sign-in failures recorded for this provider."
    const val IDP_FAILURES_COL_WHEN = "When (UTC)"
    const val IDP_FAILURES_COL_REASON = "Reason"
    const val IDP_FAILURES_COL_DOMAIN = "Email domain"
    const val IDP_FAILURES_COL_REFERENCE = "Reference"
    const val IDP_FAILURE_EMAIL_NOT_VERIFIED = "Provider did not verify the email address"
    const val IDP_FAILURE_DOMAIN_NOT_ALLOWED = "Email domain not on the allowed list"
    const val IDP_FAILURE_USERNAME_CONFLICT = "A local account already uses that email as its username"
    const val IDP_FAILURE_IDP_RETURNED_ERROR = "Rejected at the provider"
    const val IDP_FAILURE_UNRECOGNISED = "Unrecognised failure"

    const val IDP_ADD_TITLE = "Add an OIDC provider"
    const val IDP_ADD_BUTTON = "Add provider"
    const val IDP_SAVE_BUTTON = "Save provider"
    const val IDP_DELETE_BUTTON = "Delete provider"
    const val IDP_DELETE_DESCRIPTION =
        "Anyone who signs in through this provider loses that route immediately. Accounts already " +
            "linked to it keep the link but cannot use it."
    const val IDP_DELETE_CONFIRM =
        "Delete this identity provider? Anyone who signs in through it loses that route immediately, " +
            "and accounts already linked to it keep the link but cannot use it."
    const val IDP_KEY_LABEL = "Provider key"

    // Deliberately not a real vendor: a named one in a form field reads as a provider this
    // implementation has been run against, and none has.
    const val IDP_KEY_PLACEHOLDER = "workforce-sso"
    const val IDP_KEY_HINT =
        "Lower-case letters, digits and hyphens. It appears in the callback URL and cannot be " +
            "changed once saved, because accounts already linked to this provider point at it."
    const val IDP_KEY_INVALID =
        "That is not a provider key. Use 1 to 32 lower-case letters, digits or hyphens."
    const val IDP_KIND_OIDC = "OpenID Connect"
    const val IDP_DISPLAY_NAME_LABEL = "Display name"
    const val IDP_DISPLAY_NAME_PLACEHOLDER = "Workforce SSO"
    const val IDP_DISPLAY_NAME_HINT = "Shown on the sign-in button. Defaults to the provider key."
    const val IDP_ISSUER_LABEL = "Issuer URL"
    const val IDP_ISSUER_PLACEHOLDER = "https://idp.example.com"
    const val IDP_ISSUER_HINT =
        "Required for OIDC. Must match the 'iss' claim of the issuer's ID tokens; discovery is " +
            "read from {issuer}/.well-known/openid-configuration."
    const val IDP_CLIENT_ID_LABEL = "Client ID"
    const val IDP_CLIENT_ID_PLACEHOLDER = "Client ID issued by the provider"
    const val IDP_CLIENT_SECRET_LABEL = "Client Secret"
    const val IDP_SECRET_NEW_HINT = "Stored encrypted."
    const val IDP_SECRET_STORED_HINT = "Stored encrypted. Leave blank to keep existing secret."
    const val IDP_SCOPES_LABEL = "Scopes"
    const val IDP_SCOPES_HINT = "Space-separated. An OIDC provider must request 'openid'."
    const val IDP_AUTHORIZATION_ENDPOINT_LABEL = "Authorization endpoint"
    const val IDP_TOKEN_ENDPOINT_LABEL = "Token endpoint"
    const val IDP_JWKS_URI_LABEL = "JWKS URI"
    const val IDP_ENDPOINT_OVERRIDE_HINT =
        "Optional. Leave blank to use the value from the issuer's discovery document."

    // Just-in-time provisioning — the two columns that decide whether a brokered sign-in
    // may create a local account.
    const val IDP_JIT_TITLE = "Create accounts on first sign-in"
    const val IDP_JIT_ENABLE_LABEL = "Create accounts automatically"
    const val IDP_JIT_HINT =
        "When on, someone signing in through this provider for the first time gets an account " +
            "here, but only if the provider says their email is verified and its domain is " +
            "on the list below."
    const val IDP_TRUST_EMAIL_LABEL = "Trust this provider's email claim"
    const val IDP_TRUST_EMAIL_TOGGLE = "Treat the address as verified"
    const val IDP_TRUST_EMAIL_HINT =
        "Off by default: an account is created or matched only when the provider states the address " +
            "is verified. Some issuers never send that statement at all (Microsoft Entra ID among " +
            "them), so sign-in through them is refused until this is on. Turning it on also lets a " +
            "sign-in claim an existing account with the same address, and no domain list narrows " +
            "that. Turn it on only for an issuer whose addresses you control."
    const val IDP_JIT_DOMAINS_LABEL = "Allowed email domains"
    const val IDP_JIT_DOMAINS_EMPTY =
        "No domains listed, so no account is created automatically even with the toggle on. " +
            "An empty list is the feature switched off, never a wildcard."
    const val IDP_JIT_DOMAINS_HINT =
        "Untick a domain and save to remove it. Domains are stored lower-case; duplicates are " +
            "ignored."
    const val IDP_JIT_DOMAIN_ADD_LABEL = "Add a domain"
    const val IDP_JIT_DOMAIN_ADD_PLACEHOLDER = "example.com"
    const val IDP_JIT_DOMAIN_ADD_HINT = "A bare domain, one per save. It is added when you save the provider."

    // Test discovery — the setup aid, and the half of setup it cannot see.
    const val IDP_DISCOVERY_BUTTON = "Test discovery"
    const val IDP_CONNECTION_HEADING = "Connection"
    const val IDP_FAILURES_HEADING = "Recent sign-in failures"
    const val IDP_ADVANCED_SUMMARY = "Advanced endpoint overrides"
    const val IDP_ENABLE_ACTION = "Enable"
    const val IDP_DISABLE_ACTION = "Disable"
    const val IDP_CALLBACK_HINT_NEW =
        "Register this callback URL with the issuer. The provider key you enter below completes it."
    const val IDP_DISCOVERY_TITLE = "Discovery test"
    const val IDP_DISCOVERY_VERIFIED_TITLE = "What this test verified"
    const val IDP_DISCOVERY_KEYS_LABEL = "Signing keys published"
    const val IDP_DISCOVERY_KEYS_UNREAD = "The key set could not be read"
    const val IDP_DISCOVERY_NOT_VERIFIED_TITLE = "What this test did not verify"
    const val IDP_DISCOVERY_NOT_VERIFIED_REDIRECT =
        "Your redirect URI. Discovery reads what the issuer publishes; nothing here asks the " +
            "provider whether it will accept the callback URL below. A URL the provider does not " +
            "recognise is refused at the provider, after this page has said the endpoints resolve, " +
            "and shows up only under Recent sign-in failures."
    const val IDP_DISCOVERY_NOT_VERIFIED_CREDENTIALS =
        "Your client ID and client secret. Nothing in this test authenticates as this client. " +
            "The first request that does is a real sign-in."
    const val IDP_DISCOVERY_CALLBACK_LABEL = "Register this exact callback URL at the provider:"
    const val IDP_DISCOVERY_FAILED_TITLE = "Discovery did not resolve"

    /**
     * Human-readable name for a provider key. The two reserved keys keep their brand casing;
     * any other key is title-cased from its own value so a provider always has a label.
     */
    fun providerDisplayName(key: ProviderKey): String =
        when (key) {
            ProviderKey.GOOGLE -> LOGIN_PROVIDER_GOOGLE
            ProviderKey.GITHUB -> LOGIN_PROVIDER_GITHUB
            else ->
                key.value
                    .split("-")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercaseChar() } }
        }

    /** The aggregate row's count. Reads as a status, not a total, so the singular is worth having. */
    fun externalIdpConfiguredCount(count: Int): String = if (count == 1) "1 configured" else "$count configured"

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
