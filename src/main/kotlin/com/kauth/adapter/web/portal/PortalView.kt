package com.kauth.adapter.web.portal

import com.kauth.domain.model.Session
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service portal HTML views — Phase 3b.
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
        slug          : String,
        workspaceName : String,
        theme         : TenantTheme,
        error         : String?
    ): HTML.() -> Unit = {
        head { authPageHead("$workspaceName | Sign In", theme) }
        body {
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
                    action  = "/t/$slug/account/login",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method  = FormMethod.post
                ) {
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id = "username"
                            placeholder = "Enter your username"
                            attributes["autocomplete"] = "username"
                            required = true
                            attributes["autofocus"] = "true"
                        }
                    }
                    div("field") {
                        label { htmlFor = "password"; +"Password" }
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
        slug          : String,
        session       : PortalSession,
        theme         : TenantTheme,
        workspaceName : String,
        successMsg    : String?,
        errorMsg      : String?
    ): HTML.() -> Unit = {
        head { portalPageHead("Profile — $workspaceName", theme) }
        body {
            portalShell(slug, workspaceName, session.username, "profile") {
                h2(classes = "portal-section-title") { +"Profile" }

                if (successMsg != null)
                    div(classes = "alert alert-success") { +"Profile updated successfully." }
                if (!errorMsg.isNullOrBlank())
                    div(classes = "alert alert-error") { +errorMsg }

                form(
                    action  = "/t/$slug/account/profile",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method  = FormMethod.post,
                    classes = "portal-form"
                ) {
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id = "username"
                            value = session.username
                            disabled = true
                            title = "Username cannot be changed"
                        }
                        p(classes = "form-hint") { +"Username cannot be changed after account creation." }
                    }
                    div("field") {
                        label { htmlFor = "email"; +"Email address" }
                        input(type = InputType.email, name = "email") {
                            id = "email"
                            placeholder = "you@example.com"
                            attributes["autocomplete"] = "email"
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "full_name"; +"Full name" }
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
        slug          : String,
        session       : PortalSession,
        theme         : TenantTheme,
        workspaceName : String,
        sessions      : List<Session>,
        successMsg    : String?,
        errorMsg      : String?
    ): HTML.() -> Unit = {
        head { portalPageHead("Security — $workspaceName", theme) }
        body {
            portalShell(slug, workspaceName, session.username, "security") {
                h2(classes = "portal-section-title") { +"Change password" }

                if (successMsg != null)
                    div(classes = "alert alert-success") { +"Password changed successfully." }
                if (!errorMsg.isNullOrBlank())
                    div(classes = "alert alert-error") { +errorMsg }

                form(
                    action  = "/t/$slug/account/change-password",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method  = FormMethod.post,
                    classes = "portal-form"
                ) {
                    div("field") {
                        label { htmlFor = "current_password"; +"Current password" }
                        input(type = InputType.password, name = "current_password") {
                            id = "current_password"
                            required = true
                            attributes["autocomplete"] = "current-password"
                        }
                    }
                    div("field") {
                        label { htmlFor = "new_password"; +"New password" }
                        input(type = InputType.password, name = "new_password") {
                            id = "new_password"
                            placeholder = "Minimum 8 characters"
                            required = true
                            attributes["autocomplete"] = "new-password"
                        }
                    }
                    div("field") {
                        label { htmlFor = "confirm_password"; +"Confirm new password" }
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
                                            action  = "/t/$slug/account/sessions/${s.id}/revoke",
                                            method  = FormMethod.post
                                        ) {
                                            button(type = ButtonType.submit, classes = "btn-danger-sm") { +"Revoke" }
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
    // Shared <head> — login page (centered card, same as auth pages)
    // =========================================================================

    /**
     * Used only for the portal login page. Injects theme vars and links the auth
     * stylesheet so the card/field/btn classes work identically to the auth pages.
     */
    private fun HEAD.authPageHead(title: String, theme: TenantTheme) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
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
    private fun HEAD.portalPageHead(title: String, theme: TenantTheme) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
        // Theme vars first — both auth.css and portal inline CSS read from these
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-auth.css")
        // Portal-specific overrides — extend, not replace, the auth stylesheet
        style {
            unsafe {
                raw("""
                    /* Reset auth page body centering for portal layout */
                    body {
                        display: block;
                        padding: 0;
                        min-height: 100vh;
                    }

                    /* ── Shell ───────────────────────────────────────────── */
                    .portal-shell {
                        display: flex;
                        min-height: 100vh;
                        width: 100%;
                        background: var(--bg-deep);
                    }

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
                """.trimIndent())
            }
        }
    }

    // =========================================================================
    // Shared layout — authenticated page shell
    // =========================================================================

    private fun BODY.portalShell(
        slug          : String,
        workspaceName : String,
        username      : String,
        activePage    : String,
        content       : DIV.() -> Unit
    ) {
        div(classes = "portal-shell") {
            // ── Sticky sidebar ────────────────────────────────────────────
            nav(classes = "portal-nav") {
                div(classes = "portal-nav-header") {
                    p(classes = "portal-nav-workspace") { +workspaceName }
                    p(classes = "portal-nav-user") { +username }
                }
                div(classes = "portal-nav-links") {
                    a(
                        href    = "/t/$slug/account/profile",
                        classes = "portal-nav-link${if (activePage == "profile") " active" else ""}"
                    ) { +"Profile" }
                    a(
                        href    = "/t/$slug/account/security",
                        classes = "portal-nav-link${if (activePage == "security") " active" else ""}"
                    ) { +"Security" }
                }
                div(classes = "portal-nav-footer") {
                    form(action = "/t/$slug/account/logout", method = FormMethod.post) {
                        button(type = ButtonType.submit, classes = "portal-nav-link") {
                            style = "background:none;border:none;cursor:pointer;width:100%;text-align:left;font-size:13px;"
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
