package com.kauth.adapter.web.portal

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.JsIntegrity
import com.kauth.domain.model.SecurityConfig
import com.kauth.adapter.web.demoBanner
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service portal HTML views.
 *
 * Theme tokens (--color-accent, --color-text, etc.) are injected at runtime by
 * TenantTheme.toCssVars() before the portal CSS bundle is linked.
 *
 * Layout variants (selected per-tenant via PortalConfig.layout):
 *   SIDEBAR  — fixed 220px sidebar left, scrollable centered content right
 *   CENTERED — sticky topbar with horizontal tab strip, content below
 *
 * Login / MFA-challenge pages always use the auth card layout (kotauth-auth.css).
 */
object PortalView {
    private val dtf = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneOffset.UTC)

    // =========================================================================
    // Portal login — matches AuthView card layout exactly
    // =========================================================================

    fun loginPage(
        slug: String,
        workspaceName: String,
        theme: TenantTheme,
        error: String?,
    ): HTML.() -> Unit =
        {
            head { authPageHead("$workspaceName | Sign In", theme) }
            body {
                demoBanner()
                div("brand") {
                    div("brand-name") { +workspaceName }
                }
                div("card") {
                    h1("card-title") { +"Account" }
                    p("card-subtitle") { +"Sign in to manage your account" }

                    if (!error.isNullOrBlank()) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/t/$slug/account/login",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "username"
                                +"Username"
                            }
                            input(type = InputType.text, name = "username") {
                                id = "username"
                                placeholder = "Enter your username"
                                attributes["autocomplete"] = "username"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "password"
                                +"Password"
                            }
                            input(type = InputType.password, name = "password") {
                                id = "password"
                                placeholder = "Enter your password"
                                attributes["autocomplete"] = "current-password"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +"Sign in" }
                    }

                    div("footer-link") {
                        a(href = "/t/$slug/forgot-password") { +"Forgot password?" }
                    }
                }
            }
        }

    // =========================================================================
    // Profile page
    // =========================================================================

    fun profilePage(
        slug: String,
        session: PortalSession,
        theme: TenantTheme,
        workspaceName: String,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        successMsg: String?,
        errorMsg: String?,
        email: String = "",
        fullName: String = "",
    ): HTML.() -> Unit =
        {
            head { portalPageHead("Profile — $workspaceName", theme, layout) }
            body {
                portalShell(slug, workspaceName, session.username, "profile", layout, theme.logoUrl) {
                    div(classes = "page-header") {
                        h1(classes = "page-header__title") { +"Profile" }
                        p(classes = "page-header__subtitle") { +"Manage your personal information" }
                    }

                    if (successMsg != null) {
                        div(classes = "alert alert-success") { +"Profile updated successfully." }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    div(classes = "portal-section") {
                        div(classes = "profile-identity") {
                            div(classes = "profile-identity__avatar") {
                                +(fullName.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                            }
                            div(classes = "profile-identity__info") {
                                div(classes = "profile-identity__name") { +fullName }
                                div(classes = "profile-identity__username") { +"@${session.username}" }
                            }
                        }
                        div(classes = "portal-section__body") {
                            form(
                                action = "/t/$slug/account/profile",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                                classes = "portal-form",
                            ) {
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "username"
                                        +"Username"
                                    }
                                    input(type = InputType.text, name = "username") {
                                        classes = setOf("edit-field__input", "edit-field__input--mono", "edit-field__input--disabled")
                                        id = "username"
                                        value = session.username
                                        disabled = true
                                        title = "Username cannot be changed"
                                    }
                                    p(classes = "edit-field__hint") { +"Username cannot be changed after account creation." }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "email"
                                        +"Email address"
                                    }
                                    input(type = InputType.email, name = "email") {
                                        classes = setOf("edit-field__input")
                                        id = "email"
                                        value = email
                                        placeholder = "you@example.com"
                                        attributes["autocomplete"] = "email"
                                        required = true
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "full_name"
                                        +"Full name"
                                    }
                                    input(type = InputType.text, name = "full_name") {
                                        classes = setOf("edit-field__input")
                                        id = "full_name"
                                        value = fullName
                                        placeholder = "Your full name"
                                        required = true
                                    }
                                }
                                div(classes = "edit-actions") {
                                    button(type = ButtonType.submit, classes = "btn btn--primary") { +"Save changes" }
                                }
                            }
                        }
                    }

                    div(classes = "danger-zone") {
                        div(classes = "danger-zone__header") {
                            span(classes = "danger-zone__header-title") { +"Danger zone" }
                        }
                        div(classes = "danger-zone__item") {
                            div(classes = "danger-zone__item-info") {
                                div(classes = "danger-zone__item-title") { +"Delete account" }
                                div(classes = "danger-zone__item-desc") {
                                    +"Permanently deletes your account, profile, and all associated data. This cannot be undone."
                                }
                            }
                            button(
                                type = ButtonType.button,
                                classes = "btn btn--danger",
                            ) {
                                attributes["onclick"] = "document.getElementById('delete-confirm').classList.toggle('is-open')"
                                +"Delete account"
                            }
                        }
                        div(classes = "confirm-block") {
                            id = "delete-confirm"
                            p(classes = "confirm-block__label") {
                                +"Type "
                                code { +session.username }
                                +" to confirm deletion"
                            }
                            form(
                                action = "/t/$slug/account/delete",
                                method = FormMethod.post,
                            ) {
                                div(classes = "confirm-block__row") {
                                    input(type = InputType.text, name = "confirm_username") {
                                        classes = setOf("confirm-block__input")
                                        placeholder = session.username
                                        required = true
                                        attributes["autocomplete"] = "off"
                                    }
                                    button(
                                        type = ButtonType.submit,
                                        classes = "btn btn--danger",
                                    ) { +"Confirm delete" }
                                }
                            }
                        }
                    }
                }
            }
        }

    // =========================================================================
    // Security page (change password + sessions)
    // =========================================================================

    fun securityPage(
        slug: String,
        session: PortalSession,
        theme: TenantTheme,
        workspaceName: String,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        sessions: List<Session>,
        currentSessionId: Int? = null,
        successMsg: String?,
        errorMsg: String?,
        passwordPolicy: SecurityConfig = SecurityConfig(),
    ): HTML.() -> Unit =
        {
            head { portalPageHead("Security — $workspaceName", theme, layout) }
            body {
                portalShell(slug, workspaceName, session.username, "security", layout, theme.logoUrl) {
                    div(classes = "page-header") {
                        h1(classes = "page-header__title") { +"Security" }
                        p(classes = "page-header__subtitle") { +"Password and active sessions" }
                    }

                    if (successMsg != null) {
                        div(classes = "alert alert-success") { +"Password changed successfully." }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div(classes = "portal-section__header-left") {
                                span(classes = "portal-section__title") { +"Change password" }
                            }
                        }
                        div(classes = "portal-section__body") {
                            form(
                                action = "/t/$slug/account/change-password",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                                classes = "portal-form",
                            ) {
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "current_password"
                                        +"Current password"
                                    }
                                    input(type = InputType.password, name = "current_password") {
                                        classes = setOf("edit-field__input")
                                        id = "current_password"
                                        required = true
                                        attributes["autocomplete"] = "current-password"
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "new_password"
                                        +EnglishStrings.NEW_PASSWORD
                                    }
                                    input(type = InputType.password, name = "new_password") {
                                        classes = setOf("edit-field__input")
                                        id = "new_password"
                                        placeholder = EnglishStrings.passwordMinPlaceholder(passwordPolicy.passwordMinLength)
                                        required = true
                                        attributes["autocomplete"] = "new-password"
                                        attributes["data-pw-min-length"] =
                                            passwordPolicy.passwordMinLength.toString()
                                        if (passwordPolicy.passwordRequireUppercase) {
                                            attributes["data-pw-require-upper"] = "true"
                                        }
                                        if (passwordPolicy.passwordRequireNumber) {
                                            attributes["data-pw-require-number"] = "true"
                                        }
                                        if (passwordPolicy.passwordRequireSpecial) {
                                            attributes["data-pw-require-special"] = "true"
                                        }
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "confirm_password"
                                        +EnglishStrings.CONFIRM_NEW_PASSWORD
                                    }
                                    input(type = InputType.password, name = "confirm_password") {
                                        classes = setOf("edit-field__input")
                                        id = "confirm_password"
                                        placeholder = EnglishStrings.CONFIRM_PASSWORD_PLACEHOLDER
                                        required = true
                                        attributes["autocomplete"] = "new-password"
                                    }
                                }
                                div(classes = "edit-actions") {
                                    span(classes = "edit-actions__note") {
                                        +"Changing your password signs you out of all active sessions"
                                    }
                                    button(type = ButtonType.submit, classes = "btn btn--primary") { +"Change password" }
                                }
                            }
                        }
                    }

                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div(classes = "portal-section__header-left") {
                                span(classes = "portal-section__title") { +"Active sessions" }
                                span(classes = "portal-section__subtitle") { +"Devices currently signed into your account" }
                            }
                            if (sessions.size > 1) {
                                form(
                                    action = "/t/$slug/account/sessions/revoke-others",
                                    method = FormMethod.post,
                                ) {
                                    button(
                                        type = ButtonType.submit,
                                        classes = "btn btn--danger btn--sm",
                                    ) {
                                        attributes["data-confirm"] =
                                            "Sign out of all other sessions? Only your current session will remain active."
                                        +"Revoke all others"
                                    }
                                }
                            }
                        }
                        if (sessions.isEmpty()) {
                            div(classes = "portal-section__body") {
                                p(classes = "portal-empty") { +"No active sessions found." }
                            }
                        } else {
                            table(classes = "sessions-table") {
                                thead {
                                    tr {
                                        th { +"Device / IP" }
                                        th { +"Started" }
                                        th { +"Expires" }
                                        th { +"" }
                                    }
                                }
                                tbody {
                                    for (s in sessions) {
                                        val isCurrent = currentSessionId != null && s.id?.value == currentSessionId
                                        tr {
                                            td {
                                                div(classes = "session-device-label") {
                                                    +UserAgentParser.parse(s.userAgent)
                                                    if (isCurrent) {
                                                        span(classes = "session-current-pill") { +"Current" }
                                                    }
                                                }
                                                span(classes = "session-ip") { +(s.ipAddress ?: "—") }
                                            }
                                            td {
                                                span(classes = "session-time") { +dtf.format(s.createdAt) }
                                            }
                                            td {
                                                span(classes = "session-time") { +dtf.format(s.expiresAt) }
                                            }
                                            td {
                                                if (isCurrent) {
                                                    button(
                                                        type = ButtonType.button,
                                                        classes = "btn btn--danger btn--sm btn--disabled",
                                                    ) {
                                                        disabled = true
                                                        +"Revoke"
                                                    }
                                                } else {
                                                    form(
                                                        action = "/t/$slug/account/sessions/${s.id?.value}/revoke",
                                                        method = FormMethod.post,
                                                    ) {
                                                        button(
                                                            type = ButtonType.submit,
                                                            classes = "btn btn--danger btn--sm",
                                                        ) { +"Revoke" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }

    // =========================================================================
    // MFA challenge
    // =========================================================================

    fun mfaChallengePage(
        slug: String,
        workspaceName: String,
        theme: TenantTheme,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            head { authPageHead("$workspaceName | Verify Identity", theme) }
            body {
                demoBanner()
                div("brand") {
                    div("brand-name") { +workspaceName }
                }
                div("card") {
                    h1("card-title") { +"Two-Factor Authentication" }
                    p("card-subtitle") {
                        id = "challenge-subtitle"
                        +"Enter the 6-digit code from your authenticator app"
                    }

                    if (!error.isNullOrBlank()) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/t/$slug/account/mfa-challenge",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "code"
                                id = "code-label"
                                +"Verification code"
                            }
                            input(type = InputType.text, name = "code") {
                                id = "code"
                                placeholder = "000000"
                                attributes["autocomplete"] = "one-time-code"
                                attributes["inputmode"] = "numeric"
                                attributes["pattern"] = "[0-9]*"
                                maxLength = "6"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +"Verify" }
                    }

                    div("footer-link") {
                        a(href = "#") {
                            id = "recovery-toggle"
                            attributes["onclick"] = "toggleRecoveryMode(); return false;"
                            +"Use a recovery code instead"
                        }
                    }

                    div("footer-link") {
                        a(href = "/t/$slug/account/login") { +"Back to login" }
                    }
                }

                script {
                    unsafe {
                        raw(
                            """
                            var _recoveryMode = false;
                            function toggleRecoveryMode() {
                                _recoveryMode = !_recoveryMode;
                                var input    = document.getElementById('code');
                                var label    = document.getElementById('code-label');
                                var subtitle = document.getElementById('challenge-subtitle');
                                var toggle   = document.getElementById('recovery-toggle');
                                if (_recoveryMode) {
                                    label.textContent    = 'Recovery code';
                                    subtitle.textContent = 'Enter one of the 8-character recovery codes you saved during setup';
                                    toggle.textContent   = 'Use authenticator app instead';
                                    input.placeholder    = 'e.g. a1b2c3d4';
                                    input.removeAttribute('inputmode');
                                    input.removeAttribute('pattern');
                                    input.removeAttribute('maxlength');
                                } else {
                                    label.textContent    = 'Verification code';
                                    subtitle.textContent = 'Enter the 6-digit code from your authenticator app';
                                    toggle.textContent   = 'Use a recovery code instead';
                                    input.placeholder    = '000000';
                                    input.setAttribute('inputmode', 'numeric');
                                    input.setAttribute('pattern', '[0-9]*');
                                    input.setAttribute('maxlength', '6');
                                }
                                input.value = '';
                                input.focus();
                            }
                            """.trimIndent(),
                        )
                    }
                }
            }
        }

    // =========================================================================
    // MFA management page
    //   mfaEnabled = false  →  setup flow (QR + recovery codes + verification)
    //   mfaEnabled = true   →  active state + disable option
    // =========================================================================

    fun mfaPage(
        slug: String,
        session: PortalSession,
        theme: TenantTheme,
        workspaceName: String,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        mfaEnabled: Boolean,
        successMsg: String? = null,
        errorMsg: String? = null,
        noticeMsg: String? = null,
    ): HTML.() -> Unit = {
        head {
            portalPageHead("Two-Factor Auth — $workspaceName", theme, layout)
            if (!mfaEnabled) {
                script(src = "https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js") {}
            }
        }
        body {
            portalShell(slug, workspaceName, session.username, "mfa", layout, theme.logoUrl) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +"Two-Factor Authentication" }
                    p(classes = "page-header__subtitle") { +"Protect your account with an authenticator app" }
                }

                if (!noticeMsg.isNullOrBlank()) {
                    div(classes = "alert alert-warning") {
                        style = "font-size:14px; padding:12px 16px;"
                        +noticeMsg
                    }
                }

                if (successMsg != null) {
                    div(classes = "alert alert-success") {
                        +"Authenticator set up successfully. Your account is now protected with two-factor authentication."
                    }
                }
                if (!errorMsg.isNullOrBlank()) {
                    div(classes = "alert alert-error") { +errorMsg }
                }

                if (mfaEnabled) {
                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div {
                                div(classes = "portal-section__title") { +"Authenticator app" }
                            }
                            span(classes = "badge badge--active") {
                                span(classes = "badge__dot") {}
                                +"Active"
                            }
                        }
                        div(classes = "portal-section__body") {
                            p { +"Two-factor authentication is protecting your account." }
                            p {
                                style = "margin-top:6px"
                                +"When you sign in you'll be asked for a 6-digit code from your authenticator app."
                            }
                            p(classes = "form-hint") {
                                style = "margin-top:8px"
                                +(
                                    "Recovery codes were displayed once when you set up two-factor authentication. " +
                                        "To generate new codes, remove and re-enable two-factor authentication."
                                )
                            }

                            div(classes = "divider") {}

                            div {
                                id = "disable-btn-row"
                                button(classes = "btn btn--danger") {
                                    attributes["onclick"] =
                                        """
                                        document.getElementById('disable-confirm').style.display='block';
                                        document.getElementById('disable-btn-row').style.display='none';
                                        """.trimIndent()
                                    +"Remove authenticator"
                                }
                            }
                            div {
                                id = "disable-confirm"
                                style = "display:none"
                                div(classes = "alert-warning") {
                                    +(
                                        "This will remove your authenticator app and disable two-factor authentication. " +
                                            "Your account will only be protected by your password."
                                    )
                                }
                                div(classes = "mfa-action-row") {
                                    button(classes = "btn btn--danger") {
                                        id = "disable-btn"
                                        attributes["onclick"] = "disableMfa('$slug')"
                                        +"Yes, remove authenticator"
                                    }
                                    button(classes = "btn btn--ghost") {
                                        attributes["onclick"] =
                                            """
                                            document.getElementById('disable-confirm').style.display='none';
                                            document.getElementById('disable-btn-row').style.display='block';
                                            """.trimIndent()
                                        +"Cancel"
                                    }
                                }
                            }
                        }
                    }
                } else {
                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div {
                                div(classes = "portal-section__title") { +"Authenticator app" }
                            }
                            span(classes = "badge badge--warning") {
                                span(classes = "badge__dot") {}
                                +"Not configured"
                            }
                        }
                        div(classes = "portal-section__body") {
                            div {
                                id = "mfa-step-1"
                                p(classes = "form-hint") {
                                    +"Use an authenticator app to generate one-time codes. Once enabled, you'll need your phone every time you sign in. Save your recovery codes somewhere safe before finishing setup."
                                }
                                div( classes = "compatible-apps" ) {
                                    p {
                                        +"Compatible Apps"
                                    }
                                    ul(classes = "compatible-apps__list") {
                                        span(classes = "compatible-apps__item") { +"Google Authenticator" }
                                        span(classes = "compatible-apps__item") { +"Authy" }
                                        span(classes = "compatible-apps__item") { +"Microsoft Authenticator" }
                                        span(classes = "compatible-apps__item") { +"1Password" }
                                        span(classes = "compatible-apps__item") { +"Bitwarden" }
                                    }
                                }
                                button(classes = "btn btn--primary") {
                                    id = "start-btn"
                                    attributes["onclick"] = "startEnrollment('$slug')"
                                    +"Set up authenticator"
                                }
                                div(classes = "alert alert-error") {
                                    id = "enroll-error"
                                    style = "display:none;margin-top:14px"
                                }
                            }

                            div {
                                id = "mfa-step-2"
                                style = "display:none"

                                p(classes = "mfa-step-heading") { +"1. Scan this QR code" }
                                p(classes = "form-hint") {
                                    +"Open your authenticator app and scan the QR code below to add your account."
                                }
                                div(classes = "qr-container") { id = "qr-code" }
                                p(classes = "form-hint") {
                                    +"Can't scan? Enter this key manually: "
                                    span(classes = "mfa-secret-key") { id = "setup-key" }
                                }

                                div(classes = "divider") {}

                                p(classes = "mfa-step-heading") { +"2. Save your recovery codes" }
                                p {
                                    +"If you ever lose access to your authenticator app, use one of these codes to sign in. Each code works only once."
                                }
                                div(classes = "alert-warning") {
                                    +"Save these codes now — they won't be shown again after you leave this page."
                                }
                                div(classes = "recovery-codes-grid") { id = "recovery-codes" }
                                button(classes = "btn btn--ghost") {
                                    id = "copy-codes-btn"
                                    style = "margin-bottom: 4px;"
                                    attributes["onclick"] = "copyCodes()"
                                    +"Copy codes"
                                }

                                label(classes = "mfa-confirm-label") {
                                    input(type = InputType.checkBox) {
                                        id = "codes-saved"
                                        attributes["onchange"] =
                                            "document.getElementById('mfa-step-2b').style.display=this.checked?'block':'none'"
                                    }
                                    +" I've saved my recovery codes in a safe place"
                                }

                                div {
                                    id = "mfa-step-2b"
                                    style = "display:none"
                                    div(classes = "divider") {}
                                    p(classes = "mfa-step-heading") { +"3. Verify your setup" }
                                    p {
                                        +"Enter the 6-digit code shown in your authenticator app to confirm everything is working."
                                    }
                                    div(classes = "edit-field") {
                                        label(classes = "edit-field__label") {
                                            htmlFor = "totp-code"
                                            +"Verification code"
                                        }
                                        input(type = InputType.text, name = "code") {
                                            classes = setOf("edit-field__input", "edit-field__input--mono")
                                            id = "totp-code"
                                            placeholder = "000000"
                                            attributes["inputmode"] = "numeric"
                                            attributes["pattern"] = "[0-9]*"
                                            maxLength = "6"
                                            attributes["autocomplete"] = "one-time-code"
                                        }
                                    }
                                    div(classes = "alert alert-error") {
                                        id = "verify-error"
                                        style = "display:none"
                                    }
                                    button(classes = "btn btn--primary") {
                                        id = "verify-btn"
                                        attributes["onclick"] = "verifyEnrollment('$slug')"
                                        +"Confirm setup"
                                    }
                                }
                            }
                        }
                    }
                }

                // ── JavaScript ────────────────────────────────────────────────────
                script {
                    unsafe {
                        raw(
                            """
                            async function startEnrollment(slug) {
                                var btn   = document.getElementById('start-btn');
                                var errEl = document.getElementById('enroll-error');
                                btn.disabled    = true;
                                btn.textContent = 'Setting up\u2026';
                                errEl.style.display = 'none';
                                try {
                                    var res  = await fetch('/t/' + slug + '/account/mfa/enroll', { method: 'POST' });
                                    var data = await res.json();
                                    if (!res.ok) {
                                        errEl.textContent   = data.error === 'already_enrolled'
                                            ? 'An authenticator is already configured. Refresh the page.'
                                            : 'Failed to start setup. Please try again.';
                                        errEl.style.display = 'block';
                                        btn.disabled        = false;
                                        btn.textContent     = 'Set up authenticator';
                                        return;
                                    }
                                    document.getElementById('mfa-step-1').style.display = 'none';
                                    document.getElementById('mfa-step-2').style.display = 'block';
                                    new QRCode(document.getElementById('qr-code'), {
                                        text: data.totp_uri, width: 200, height: 200,
                                        colorDark: '#000000', colorLight: '#ffffff',
                                        correctLevel: QRCode.CorrectLevel.M
                                    });
                                    var m = data.totp_uri.match(/secret=([A-Z2-7]+)/i);
                                    if (m) document.getElementById('setup-key').textContent = m[1];
                                    window._codes = data.recovery_codes;
                                    var grid = document.getElementById('recovery-codes');
                                    grid.innerHTML = '';
                                    data.recovery_codes.forEach(function(c) {
                                        var s = document.createElement('span');
                                        s.className = 'recovery-code';
                                        s.textContent = c;
                                        grid.appendChild(s);
                                    });
                                } catch (e) {
                                    errEl.textContent   = 'Network error. Please check your connection and try again.';
                                    errEl.style.display = 'block';
                                    btn.disabled        = false;
                                    btn.textContent     = 'Set up authenticator';
                                }
                            }

                            async function verifyEnrollment(slug) {
                                var code  = document.getElementById('totp-code').value.trim();
                                var errEl = document.getElementById('verify-error');
                                var btn   = document.getElementById('verify-btn');
                                errEl.style.display = 'none';
                                if (!/^\d{6}${'$'}/.test(code)) {
                                    errEl.textContent   = 'Please enter the 6-digit code from your authenticator app.';
                                    errEl.style.display = 'block';
                                    return;
                                }
                                btn.disabled    = true;
                                btn.textContent = 'Verifying\u2026';
                                try {
                                    var body = new URLSearchParams({ code: code });
                                    var res  = await fetch('/t/' + slug + '/account/mfa/verify', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                        body: body
                                    });
                                    var data = await res.json();
                                    if (res.ok) {
                                        window.location.href = '/t/' + slug + '/account/mfa?success=true';
                                    } else {
                                        errEl.textContent   = data.error === 'invalid_code'
                                            ? 'Incorrect code. Check your device clock is accurate and try again.'
                                            : 'Verification failed. Please try again.';
                                        errEl.style.display = 'block';
                                        btn.disabled        = false;
                                        btn.textContent     = 'Confirm setup';
                                    }
                                } catch (e) {
                                    errEl.textContent   = 'Network error. Please try again.';
                                    errEl.style.display = 'block';
                                    btn.disabled        = false;
                                    btn.textContent     = 'Confirm setup';
                                }
                            }

                            async function disableMfa(slug) {
                                var btn = document.getElementById('disable-btn');
                                btn.disabled    = true;
                                btn.textContent = 'Removing\u2026';
                                try {
                                    var res = await fetch('/t/' + slug + '/account/mfa/disable', { method: 'POST' });
                                    if (res.ok) {
                                        window.location.reload();
                                    } else {
                                        btn.disabled    = false;
                                        btn.textContent = 'Yes, remove authenticator';
                                        alert('Failed to remove authenticator. Please try again.');
                                    }
                                } catch (e) {
                                    btn.disabled    = false;
                                    btn.textContent = 'Yes, remove authenticator';
                                    alert('Network error. Please try again.');
                                }
                            }

                            function copyCodes() {
                                if (!window._codes) return;
                                navigator.clipboard.writeText(window._codes.join('\n')).then(function() {
                                    var btn = document.getElementById('copy-codes-btn');
                                    btn.textContent = 'Copied!';
                                    setTimeout(function() { btn.textContent = 'Copy codes'; }, 2000);
                                });
                            }
                            """.trimIndent(),
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // Shared <head> — login page (centered card, same as auth pages)
    // =========================================================================

    /**
     * Used only for the portal login page. Injects theme vars and links the auth
     * stylesheet so the card/field/btn classes work identically to the auth pages.
     */
    private fun HEAD.authPageHead(
        title: String,
        theme: TenantTheme,
    ) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
        // Favicon
        if (theme.faviconUrl != null) {
            link(rel = "icon", href = theme.faviconUrl)
        } else {
            link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
                attributes["sizes"] =
                    "32x32"
            }
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
                attributes["sizes"] =
                    "16x16"
            }
        }
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = theme.googleFontsUrl)
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-auth.css?v=${AppInfo.assetVersion}")
    }

    // =========================================================================
    // Shared <head> — authenticated portal pages
    // =========================================================================

    private fun HEAD.portalPageHead(
        title: String,
        theme: TenantTheme,
        layout: PortalLayout,
    ) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
        if (theme.faviconUrl != null) {
            link(rel = "icon", href = theme.faviconUrl)
        } else {
            link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
                attributes["sizes"] = "32x32"
            }
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
                attributes["sizes"] = "16x16"
            }
        }
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = theme.googleFontsUrl)
        style { unsafe { +theme.toCssVars() } }
        val cssBundle = when (layout) {
            PortalLayout.SIDEBAR -> "/static/kotauth-portal-sidenav.css?v=${AppInfo.assetVersion}"
            PortalLayout.CENTERED -> "/static/kotauth-portal-tabnav.css?v=${AppInfo.assetVersion}"
        }
        link(rel = "stylesheet", href = cssBundle)
        script(src = "/static/js/kotauth-portal.min.js?v=${AppInfo.assetVersion}") {
            attributes["defer"] = "true"
            JsIntegrity.portal?.let { attributes["integrity"] = it }
            attributes["crossorigin"] = "anonymous"
        }
    }

    // =========================================================================
    // Shared layout — authenticated page shell (dispatches by layout)
    // =========================================================================

    private fun BODY.portalShell(
        slug: String,
        workspaceName: String,
        username: String,
        activePage: String,
        layout: PortalLayout,
        logoUrl: String? = null,
        content: DIV.() -> Unit,
    ) {
        demoBanner()
        when (layout) {
            PortalLayout.SIDEBAR -> portalShellSidenav(slug, workspaceName, username, activePage, logoUrl, content)
            PortalLayout.CENTERED -> portalShellTabnav(slug, workspaceName, username, activePage, logoUrl, content)
        }

        // Shared confirmation dialog — same pattern as admin shell
        dialog("confirm-dialog") {
            id = "confirm-dialog"
            div("confirm-dialog__card") {
                div("confirm-dialog__body") {
                    p("confirm-dialog__title") {
                        id = "confirm-dialog-title"
                        +"Confirm"
                    }
                    p("confirm-dialog__message") {
                        id = "confirm-dialog-message"
                    }
                }
                div("confirm-dialog__actions") {
                    button(classes = "btn btn--ghost") {
                        id = "confirm-dialog-cancel"
                        +"Cancel"
                    }
                    button(classes = "btn btn--danger") {
                        id = "confirm-dialog-ok"
                        +"Confirm"
                    }
                }
            }
        }
    }

    private fun BODY.portalShellSidenav(
        slug: String,
        workspaceName: String,
        username: String,
        activePage: String,
        logoUrl: String? = null,
        content: DIV.() -> Unit,
    ) {
        aside(classes = "portal-sidebar") {
            div(classes = "portal-sidebar__brand") {
                if (logoUrl != null) {
                    img(src = logoUrl, alt = workspaceName, classes = "portal-sidebar__brand-logo") {
                        width = "32"
                        height = "32"
                    }
                } else {
                    div(classes = "portal-sidebar__brand-mark") {
                        +workspaceName.split(" ")
                            .take(2)
                            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            .joinToString("")
                            .ifEmpty { "K" }
                    }
                }
                div(classes = "portal-sidebar__brand-info") {
                    span(classes = "portal-sidebar__org") { +workspaceName }
                    span(classes = "portal-sidebar__app") { +"My Account" }
                }
            }
            nav(classes = "portal-nav") {
                attributes["role"] = "navigation"
                attributes["aria-label"] = "Account settings"
                span(classes = "portal-nav__label") { +"Account" }
                portalNavItems(slug, activePage, "portal-nav__item")
            }
            div(classes = "portal-sidebar__footer") {
                div(classes = "portal-user") {
                    div(classes = "portal-user__avatar") {
                        attributes["aria-hidden"] = "true"
                        +(username.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                    }
                    div(classes = "portal-user__info") {
                        span(classes = "portal-user__name") { +username }
                        span(classes = "portal-user__email") { +"@$username" }
                    }
                }
                form(action = "/t/$slug/account/logout", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "portal-signout") {
                        attributes["aria-label"] = "Sign out"
                        consumer.onTagContentUnsafe {
                            +"""<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>"""
                        }
                        +"Sign out"
                    }
                }
            }
        }
        div(classes = "portal-main-wrap") {
            div(classes = "portal-main") { content() }
        }
    }

    private fun BODY.portalShellTabnav(
        slug: String,
        workspaceName: String,
        username: String,
        activePage: String,
        logoUrl: String? = null,
        content: DIV.() -> Unit,
    ) {
        val initials = workspaceName.split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "K" }

        header(classes = "portal-topbar") {
            div(classes = "portal-topbar__inner") {
                div(classes = "portal-topbar__brand") {
                    if (logoUrl != null) {
                        img(src = logoUrl, alt = workspaceName, classes = "portal-topbar__brand-logo") {
                            width = "28"
                            height = "28"
                        }
                    } else {
                        div(classes = "portal-topbar__brand-mark") { +initials }
                    }
                    span(classes = "portal-topbar__org") { +workspaceName }
                }
                div(classes = "portal-topbar__sep") { attributes["aria-hidden"] = "true" }
                span(classes = "portal-topbar__page-title") { +"Account settings" }
                div(classes = "portal-topbar__spacer") {}
                div(classes = "portal-topbar__user") {
                    div(classes = "portal-topbar__avatar") {
                        attributes["aria-label"] = "Signed in as $username"
                        +(username.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                    }
                    span(classes = "portal-topbar__username") { +username }
                }
                form(action = "/t/$slug/account/logout", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "portal-topbar__signout") {
                        attributes["aria-label"] = "Sign out"
                        consumer.onTagContentUnsafe {
                            +"""<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>"""
                        }
                        +"Sign out"
                    }
                }
            }
        }
        nav(classes = "portal-tabnav") {
            attributes["role"] = "navigation"
            attributes["aria-label"] = "Account sections"
            div(classes = "portal-tabnav__inner") {
                portalNavItems(slug, activePage, "portal-tabnav__item")
            }
        }
        div(classes = "portal-content") { content() }
    }

    private fun FlowContent.portalNavItems(
        slug: String,
        activePage: String,
        linkClass: String,
    ) {
        a(
            href = "/t/$slug/account/profile",
            classes = "$linkClass${if (activePage == "profile") " is-active" else ""}",
        ) { +"Profile" }
        a(
            href = "/t/$slug/account/security",
            classes = "$linkClass${if (activePage == "security") " is-active" else ""}",
        ) { +"Security" }
        a(
            href = "/t/$slug/account/mfa",
            classes = "$linkClass${if (activePage == "mfa") " is-active" else ""}",
        ) { +"Two-Factor Auth" }
    }
}
