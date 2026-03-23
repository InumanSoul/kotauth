package com.kauth.adapter.web.portal

import com.kauth.adapter.web.demoBanner
import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service portal HTML views.
 *
 * Reuses kotauth-auth.css tokens (--accent, --text, --muted, --bg-*, --border, --radius)
 * so the portal inherits per-tenant theming automatically.
 *
 * Layout:
 *   Login page  — same card/centered layout as AuthView (same CSS classes)
 *   Authenticated pages — fixed sidebar left, scrollable content centered in remaining space
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
        successMsg: String?,
        errorMsg: String?,
    ): HTML.() -> Unit =
        {
            head { portalPageHead("Profile — $workspaceName", theme) }
            body {
                portalShell(slug, workspaceName, session.username, "profile") {
                    h2(classes = "portal-section-title") { +"Profile" }

                    if (successMsg != null) {
                        div(classes = "alert alert-success") { +"Profile updated successfully." }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    form(
                        action = "/t/$slug/account/profile",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                        classes = "portal-form",
                    ) {
                        div("field") {
                            label {
                                htmlFor = "username"
                                +"Username"
                            }
                            input(type = InputType.text, name = "username") {
                                id = "username"
                                value = session.username
                                disabled = true
                                title = "Username cannot be changed"
                            }
                            p(classes = "form-hint") { +"Username cannot be changed after account creation." }
                        }
                        div("field") {
                            label {
                                htmlFor = "email"
                                +"Email address"
                            }
                            input(type = InputType.email, name = "email") {
                                id = "email"
                                placeholder = "you@example.com"
                                attributes["autocomplete"] = "email"
                                required = true
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "full_name"
                                +"Full name"
                            }
                            input(type = InputType.text, name = "full_name") {
                                id = "full_name"
                                placeholder = "Your full name"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +"Save changes" }
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
        sessions: List<Session>,
        successMsg: String?,
        errorMsg: String?,
    ): HTML.() -> Unit =
        {
            head { portalPageHead("Security — $workspaceName", theme) }
            body {
                portalShell(slug, workspaceName, session.username, "security") {
                    h2(classes = "portal-section-title") { +"Change password" }

                    if (successMsg != null) {
                        div(classes = "alert alert-success") { +"Password changed successfully." }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    form(
                        action = "/t/$slug/account/change-password",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                        classes = "portal-form",
                    ) {
                        div("field") {
                            label {
                                htmlFor = "current_password"
                                +"Current password"
                            }
                            input(type = InputType.password, name = "current_password") {
                                id = "current_password"
                                required = true
                                attributes["autocomplete"] = "current-password"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "new_password"
                                +"New password"
                            }
                            input(type = InputType.password, name = "new_password") {
                                id = "new_password"
                                placeholder = "Minimum 8 characters"
                                required = true
                                attributes["autocomplete"] = "new-password"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "confirm_password"
                                +"Confirm new password"
                            }
                            input(type = InputType.password, name = "confirm_password") {
                                id = "confirm_password"
                                placeholder = "Repeat your new password"
                                required = true
                                attributes["autocomplete"] = "new-password"
                            }
                        }
                        p(classes = "form-hint") { +"Changing your password will sign you out of all active sessions." }
                        button(type = ButtonType.submit, classes = "btn") { +"Change password" }
                    }

                    hr(classes = "portal-divider")

                    h2(classes = "portal-section-title") { +"Active sessions" }
                    if (sessions.isEmpty()) {
                        p(classes = "portal-empty") { +"No active sessions found." }
                    } else {
                        table(classes = "portal-table") {
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
                                    tr {
                                        td { +(s.ipAddress ?: "—") }
                                        td { +dtf.format(s.createdAt) }
                                        td { +dtf.format(s.expiresAt) }
                                        td {
                                            form(
                                                action = "/t/$slug/account/sessions/${s.id?.value}/revoke",
                                                method = FormMethod.post,
                                            ) {
                                                button(
                                                    type = ButtonType.submit,
                                                    classes = "btn-danger-sm",
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
        mfaEnabled: Boolean,
        successMsg: String? = null,
        errorMsg: String? = null,
        noticeMsg: String? = null, // prominent banner used when MFA setup is required
    ): HTML.() -> Unit = {
        head {
            portalPageHead("Two-Factor Auth — $workspaceName", theme)
            // QR code renderer — only loaded on this page, only needed during setup
            if (!mfaEnabled) {
                script(src = "https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js") {}
            }
            style {
                unsafe {
                    raw(
                        """
                        /* ── MFA status badge ──────────────────────────────── */
                        .mfa-status-row {
                            display: flex;
                            align-items: center;
                            gap: 12px;
                            margin-bottom: 20px;
                        }
                        .mfa-status-row p { margin: 0; color: var(--muted); font-size: 14px; }
                        .mfa-badge {
                            display: inline-flex;
                            align-items: center;
                            gap: 6px;
                            padding: 3px 10px;
                            border-radius: 999px;
                            font-size: 12px;
                            font-weight: 600;
                            white-space: nowrap;
                            flex-shrink: 0;
                        }
                        .mfa-badge.active {
                            background: rgba(34,197,94,.12);
                            color: #4ade80;
                            border: 1px solid rgba(34,197,94,.25);
                        }
                        .mfa-badge.active::before {
                            content: '';
                            display: inline-block;
                            width: 6px; height: 6px;
                            border-radius: 50%;
                            background: #4ade80;
                        }
                        .mfa-badge.inactive {
                            background: rgba(148,163,184,.08);
                            color: var(--muted);
                            border: 1px solid var(--border);
                        }
                        /* ── Step headings ─────────────────────────────────── */
                        .mfa-step-heading {
                            font-size: 14px;
                            font-weight: 600;
                            color: var(--text);
                            margin: 0 0 6px 0;
                        }
                        /* ── QR container ──────────────────────────────────── */
                        .qr-container {
                            display: inline-block;
                            padding: 12px;
                            background: #ffffff;
                            border-radius: var(--radius);
                            border: 1px solid var(--border);
                            margin: 12px 0 8px;
                            line-height: 0;
                        }
                        /* ── Manual setup key ──────────────────────────────── */
                        .mfa-secret-key {
                            font-family: monospace;
                            font-size: 13px;
                            background: var(--bg-input);
                            border: 1px solid var(--border);
                            padding: 2px 6px;
                            border-radius: 4px;
                            letter-spacing: .08em;
                            color: var(--text);
                            word-break: break-all;
                            user-select: all;
                        }
                        /* ── Recovery codes grid ───────────────────────────── */
                        .recovery-codes-grid {
                            display: grid;
                            grid-template-columns: repeat(4, 1fr);
                            gap: 8px;
                            margin: 12px 0 10px;
                            max-width: 440px;
                        }
                        .recovery-code {
                            font-family: monospace;
                            font-size: 13px;
                            background: var(--bg-input);
                            border: 1px solid var(--border);
                            border-radius: var(--radius);
                            padding: 8px 6px;
                            text-align: center;
                            color: var(--text);
                            letter-spacing: .06em;
                        }
                        /* ── "I've saved" checkbox ─────────────────────────── */
                        .mfa-confirm-label {
                            display: flex;
                            align-items: center;
                            gap: 8px;
                            font-size: 13px;
                            color: var(--text);
                            cursor: pointer;
                            margin: 14px 0 0;
                        }
                        .mfa-confirm-label input[type=checkbox] {
                            width: 15px; height: 15px;
                            flex-shrink: 0;
                            cursor: pointer;
                            accent-color: var(--accent);
                        }
                        /* ── Action row (disable confirm) ──────────────────── */
                        .mfa-action-row {
                            display: flex;
                            gap: 10px;
                            margin-top: 16px;
                            align-items: center;
                            flex-wrap: wrap;
                        }
                        /* ── Warning alert ─────────────────────────────────── */
                        .alert-warning {
                            background: rgba(234,179,8,.08);
                            border: 1px solid rgba(234,179,8,.25);
                            color: #fbbf24;
                            padding: 10px 14px;
                            border-radius: var(--radius);
                            font-size: 13px;
                            margin: 12px 0 0;
                        }
                        /* ── Buttons ───────────────────────────────────────── */
                        .btn-danger {
                            background: #dc2626;
                            color: #fff;
                            border: none;
                            padding: 0.65rem 1.25rem;
                            border-radius: var(--radius);
                            cursor: pointer;
                            font-size: 14px;
                            font-family: inherit;
                            font-weight: 500;
                            transition: background .15s;
                        }
                        .btn-danger:hover    { background: #b91c1c; }
                        .btn-danger:disabled { opacity: .55; cursor: not-allowed; }
                        .btn-danger-outline {
                            background: transparent;
                            color: #f87171;
                            border: 1px solid rgba(220,38,38,.6);
                            padding: 0.65rem 1.25rem;
                            border-radius: var(--radius);
                            cursor: pointer;
                            font-size: 14px;
                            font-family: inherit;
                            font-weight: 500;
                            transition: background .15s;
                        }
                        .btn-danger-outline:hover { background: rgba(220,38,38,.08); }
                        .btn-outline {
                            background: transparent;
                            color: var(--text);
                            border: 1px solid var(--border);
                            padding: 0.65rem 1.25rem;
                            border-radius: var(--radius);
                            cursor: pointer;
                            font-size: 14px;
                            font-family: inherit;
                            font-weight: 500;
                            transition: background .15s;
                        }
                        .btn-outline:hover    { background: var(--bg-input); }
                        .btn-outline:disabled { opacity: .55; cursor: not-allowed; }
                        /* ── Secondary spacing helper ──────────────────────── */
                        .mfa-hint-row { margin-top: 6px; }
                        """.trimIndent(),
                    )
                }
            }
        }
        body {
            portalShell(slug, workspaceName, session.username, "mfa") {
                h2(classes = "portal-section-title") { +"Two-Factor Authentication" }

                // Prominent notice — shown when the user is redirected here because MFA
                // enrollment is required by the tenant policy but not yet configured.
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
                    // ── Active state ──────────────────────────────────────────────
                    div(classes = "mfa-status-row") {
                        span(classes = "mfa-badge active") { +"Active" }
                        p { +"Two-factor authentication is protecting your account." }
                    }
                    p { +"When you sign in you'll be asked for a 6-digit code from your authenticator app." }
                    p(classes = "form-hint mfa-hint-row") {
                        +(
                            "Recovery codes were displayed once when you set up two-factor authentication. " +
                                "To generate new codes, remove and re-enable two-factor authentication."
                        )
                    }

                    hr(classes = "portal-divider")

                    div {
                        id = "disable-btn-row"
                        button(classes = "btn-danger-outline") {
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
                            button(classes = "btn-danger") {
                                id = "disable-btn"
                                attributes["onclick"] = "disableMfa('$slug')"
                                +"Yes, remove authenticator"
                            }
                            button(classes = "btn-outline") {
                                attributes["onclick"] =
                                    """
                                    document.getElementById('disable-confirm').style.display='none';
                                    document.getElementById('disable-btn-row').style.display='block';
                                    """.trimIndent()
                                +"Cancel"
                            }
                        }
                    }
                } else {
                    // ── Setup state ───────────────────────────────────────────────
                    div(classes = "mfa-status-row") {
                        span(classes = "mfa-badge inactive") { +"Not configured" }
                        p { +"Add an extra layer of security to your account." }
                    }

                    // Step 1 — intro
                    div {
                        id = "mfa-step-1"
                        p {
                            +"You'll need an authenticator app such as "
                            strong { +"Google Authenticator" }
                            +", "
                            strong { +"Authy" }
                            +", or "
                            strong { +"1Password" }
                            +" to get started."
                        }
                        p(classes = "form-hint") {
                            style = "margin-bottom: 20px;"
                            +"Once enabled, you'll need your phone to sign in. Make sure you save the recovery codes somewhere safe."
                        }
                        button(classes = "btn") {
                            id = "start-btn"
                            attributes["onclick"] = "startEnrollment('$slug')"
                            +"Set up authenticator"
                        }
                        div(classes = "alert alert-error") {
                            id = "enroll-error"
                            style = "display:none;margin-top:14px"
                        }
                    }

                    // Step 2 — QR code + recovery codes + verification
                    div {
                        id = "mfa-step-2"
                        style = "display:none"

                        p(classes = "mfa-step-heading") { +"1. Scan this QR code" }
                        p(
                            classes = "form-hint",
                        ) { +"Open your authenticator app and scan the QR code below to add your account." }
                        div(classes = "qr-container") { id = "qr-code" }
                        p(classes = "form-hint") {
                            +"Can't scan? Enter this key manually: "
                            span(classes = "mfa-secret-key") { id = "setup-key" }
                        }

                        hr(classes = "portal-divider")

                        p(classes = "mfa-step-heading") { +"2. Save your recovery codes" }
                        p {
                            +"If you ever lose access to your authenticator app, use one of these codes to sign in. Each code works only once."
                        }
                        div(
                            classes = "alert-warning",
                        ) { +"Save these codes now — they won't be shown again after you leave this page." }
                        div(classes = "recovery-codes-grid") { id = "recovery-codes" }
                        button(classes = "btn-outline") {
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

                        // Step 2b — verification (gated behind the checkbox)
                        div {
                            id = "mfa-step-2b"
                            style = "display:none"
                            hr(classes = "portal-divider")
                            p(classes = "mfa-step-heading") { +"3. Verify your setup" }
                            p {
                                +"Enter the 6-digit code shown in your authenticator app to confirm everything is working."
                            }
                            div("field") {
                                label {
                                    htmlFor = "totp-code"
                                    +"Verification code"
                                }
                                input(type = InputType.text, name = "code") {
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
                            button(classes = "btn") {
                                id = "verify-btn"
                                attributes["onclick"] = "verifyEnrollment('$slug')"
                                +"Confirm setup"
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
        // Theme vars first — stylesheet reads from these
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-auth.css")
    }

    // =========================================================================
    // Shared <head> — authenticated portal pages (sidebar layout)
    // =========================================================================

    /**
     * Used for authenticated portal pages. Extends the auth stylesheet with
     * portal-specific layout classes while keeping all token references consistent.
     */
    private fun HEAD.portalPageHead(
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
        // Theme vars first — both auth.css and portal inline CSS read from these
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-auth.css")
        // Portal-specific overrides — extend, not replace, the auth stylesheet
        style {
            unsafe {
                raw(
                    """
                    /* ── Sidebar ─────────────────────────────────────────── */
                    .portal-nav {
                        width: 220px;
                        flex-shrink: 0;
                        position: sticky;
                        top: 0;
                        height: 100vh;
                        overflow-y: auto;
                        background: var(--bg-card);
                        border-right: 1px solid var(--border);
                        display: flex;
                        flex-direction: column;
                    }
                    .portal-nav-header {
                        padding: 24px 20px 20px;
                        border-bottom: 1px solid var(--border);
                    }
                    .portal-nav-workspace {
                        font-size: 10px;
                        text-transform: uppercase;
                        letter-spacing: .1em;
                        color: var(--muted);
                        margin: 0 0 4px 0;
                    }
                    .portal-nav-user {
                        font-size: 13px;
                        font-weight: 600;
                        color: var(--text);
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .portal-nav-links {
                        padding: 12px 0;
                        flex: 1;
                    }
                    .portal-nav-link {
                        display: block;
                        padding: 9px 20px;
                        font-size: 13px;
                        color: var(--muted);
                        text-decoration: none;
                        transition: color .15s, background .15s;
                    }
                    .portal-nav-link:hover {
                        color: var(--text);
                        background: var(--bg-input);
                    }
                    .portal-nav-link.active {
                        color: var(--text);
                        background: var(--bg-input);
                        font-weight: 500;
                    }
                    .portal-nav-footer {
                        padding: 16px 20px;
                        border-top: 1px solid var(--border);
                    }

                    /* ── Main content area ───────────────────────────────── */
                    .portal-main-wrap {
                        flex: 1;
                        overflow-y: auto;
                        display: flex;
                        justify-content: center;
                        padding: 48px 40px;
                    }
                    .portal-main {
                        width: 100%;
                        max-width: 600px;
                    }

                    /* ── Content primitives ──────────────────────────────── */
                    .portal-section-title {
                        font-size: 18px;
                        font-weight: 600;
                        color: var(--text);
                        margin: 0 0 24px 0;
                    }
                    .portal-form {
                        display: flex;
                        flex-direction: column;
                        gap: 4px;
                        max-width: 440px;
                    }
                    .form-hint {
                        font-size: 12px;
                        color: var(--muted);
                        margin: 3px 0 0 0;
                    }
                    .portal-divider {
                        border: none;
                        border-top: 1px solid var(--border);
                        margin: 36px 0;
                    }
                    .portal-empty {
                        color: var(--muted);
                        font-size: 13px;
                        margin-top: 8px;
                    }

                    /* ── Sessions table ──────────────────────────────────── */
                    .portal-table {
                        width: 100%;
                        border-collapse: collapse;
                        font-size: 13px;
                        color: var(--text);
                        margin-top: 8px;
                    }
                    .portal-table th {
                        text-align: left;
                        padding: 8px 12px;
                        color: var(--muted);
                        border-bottom: 1px solid var(--border);
                        font-weight: 500;
                        font-size: 11px;
                        text-transform: uppercase;
                        letter-spacing: .05em;
                    }
                    .portal-table td {
                        padding: 11px 12px;
                        border-bottom: 1px solid var(--border);
                        vertical-align: middle;
                    }

                    /* ── Danger button (session revoke) ──────────────────── */
                    .btn-danger-sm {
                        background: transparent;
                        border: 1px solid #dc2626;
                        color: #f87171;
                        padding: 4px 12px;
                        border-radius: calc(var(--radius) - 2px);
                        cursor: pointer;
                        font-size: 12px;
                        font-family: inherit;
                        transition: background .15s;
                    }
                    .btn-danger-sm:hover {
                        background: rgba(220, 38, 38, .15);
                    }

                    /* ── btn width override inside portal forms ──────────── */
                    .portal-form .btn {
                        width: auto;
                        padding: 0.75rem 1.5rem;
                        margin-top: 8px;
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    // =========================================================================
    // Shared layout — authenticated page shell
    // =========================================================================

    private fun BODY.portalShell(
        slug: String,
        workspaceName: String,
        username: String,
        activePage: String,
        content: DIV.() -> Unit,
    ) {
        demoBanner()
        div(classes = "portal-shell") {
            // ── Sticky sidebar ────────────────────────────────────────────
            nav(classes = "portal-nav") {
                div(classes = "portal-nav-header") {
                    p(classes = "portal-nav-workspace") { +workspaceName }
                    p(classes = "portal-nav-user") { +username }
                }
                div(classes = "portal-nav-links") {
                    a(
                        href = "/t/$slug/account/profile",
                        classes = "portal-nav-link${if (activePage == "profile") " active" else ""}",
                    ) { +"Profile" }
                    a(
                        href = "/t/$slug/account/security",
                        classes = "portal-nav-link${if (activePage == "security") " active" else ""}",
                    ) { +"Security" }
                    a(
                        href = "/t/$slug/account/mfa",
                        classes = "portal-nav-link${if (activePage == "mfa") " active" else ""}",
                    ) { +"Two-Factor Auth" }
                }
                div(classes = "portal-nav-footer") {
                    form(action = "/t/$slug/account/logout", method = FormMethod.post) {
                        button(type = ButtonType.submit, classes = "portal-nav-link") {
                            style =
                                "background:none;border:none;cursor:pointer;width:100%;text-align:left;font-size:13px;"
                            +"Sign out"
                        }
                    }
                }
            }

            // ── Centered content area ─────────────────────────────────────
            div(classes = "portal-main-wrap") {
                div(classes = "portal-main") {
                    content()
                }
            }
        }
    }
}
