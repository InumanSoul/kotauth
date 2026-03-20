package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.TenantTheme
import kotlinx.html.*

/**
 * Admin console shell layout.
 *
 * Contains the full page wrapper: `<head>`, top bar, icon rail,
 * context sidebar, and main content area. Extracted from AdminView
 * to keep the shell concerns isolated from page-specific rendering.
 *
 * All functions are internal to the admin package — page view files
 * call [adminShell] to wrap their content.
 *
 * Contains adminHead, adminShell, railItem, ctxLink, and context panel renderers.
 */

internal fun HEAD.adminHead(
    pageTitle: String,
    theme: TenantTheme = TenantTheme.DEFAULT,
) {
    title { +"KotAuth — $pageTitle" }
    meta(charset = "UTF-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
    link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
    link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
        attributes["sizes"] = "32x32"
    }
    link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
        attributes["sizes"] = "16x16"
    }
    style { unsafe { +theme.toCssVars() } }
    link(rel = "stylesheet", href = "/static/kotauth-admin.css")
    style {
        unsafe {
            +(
                "@import url('https://fonts.googleapis.com/css2?" +
                    "family=Inter:wght@400;500;600;700" +
                    "&family=Inconsolata:wght@400;500;700&display=swap');"
            )
        }
    }
}

// ─── Shell ──────────────────────────────────────────────────────────────────

/**
 * Full admin shell wrapper.
 *
 * Renders `<head>`, topbar, rail, context sidebar, and main content area.
 * Page views pass their content as the trailing lambda.
 *
 * @param pageTitle   Browser tab title
 * @param appInfo     Build metadata for version display
 * @param activeRail  Highlighted rail section: "apps"|"directory"|"security"|"logs"|"settings"
 * @param allWorkspaces (slug, displayName) pairs for the workspace switcher
 * @param workspaceName Display name of the current workspace
 * @param workspaceSlug Current workspace slug; null = no workspace selected (dashboard)
 * @param apps        (slug, name) pairs for the ctx panel application list
 * @param activeAppSlug Highlighted app in the ctx panel
 * @param activeAppSection Highlighted sub-section in the ctx panel
 * @param loggedInAs  Username for the profile avatar
 * @param content     Page content lambda
 */
internal fun HTML.adminShell(
    pageTitle: String,
    appInfo: AppInfo = AdminView.shellAppInfo,
    activeRail: String = "apps",
    allWorkspaces: List<Pair<String, String>> = emptyList(),
    workspaceName: String = "KotAuth",
    workspaceSlug: String? = null,
    apps: List<Pair<String, String>> = emptyList(),
    activeAppSlug: String? = null,
    activeAppSection: String = "overview",
    loggedInAs: String,
    content: DIV.() -> Unit,
) {
    head { adminHead(pageTitle) }
    body {
        div("shell") {
            // ── Top bar ──────────────────────────────────────────────
            div("shell-topbar") {
                details("ws-dropdown") {
                    summary("ws-switcher") {
                        div("ws-badge") {
                            +(workspaceName.firstOrNull()?.uppercaseChar()?.toString() ?: "K")
                        }
                        div("ws-meta") {
                            span("ws-name") { +workspaceName }
                            span("ws-label") { +"workspace" }
                        }
                        span("ws-chevron-icon") {}
                    }
                    div("ws-dropdown-menu") {
                        if (allWorkspaces.isEmpty()) {
                            span("ws-dropdown-empty") { +"No workspaces yet" }
                        } else {
                            allWorkspaces.forEach { (slug, name) ->
                                val isActive = slug == workspaceSlug
                                a(
                                    href = "/admin/workspaces/$slug",
                                    classes = "ws-dropdown-item${if (isActive) " active" else ""}",
                                ) {
                                    div("ws-dropdown-item-badge") {
                                        +(
                                            name.firstOrNull()?.uppercaseChar()?.toString()
                                                ?: slug.first().uppercaseChar().toString()
                                        )
                                    }
                                    span("ws-dropdown-item-name") { +name }
                                    if (isActive) span("ws-dropdown-item-check") { +"✓" }
                                }
                            }
                        }
                        a(
                            href = "/admin/workspaces/new",
                            classes = "ws-dropdown-item ws-dropdown-create",
                        ) { +"Add a workspace" }
                    }
                }

                div("topbar-search-wrap") {
                    input(type = InputType.search, classes = "topbar-search") {
                        placeholder = "Search apps, users, roles…"
                    }
                }

                div("topbar-right") {
                    div("topbar-avatar") {
                        attributes["title"] = "Signed in as $loggedInAs"
                        +(loggedInAs.firstOrNull()?.uppercaseChar()?.toString() ?: "A")
                    }
                    form(
                        action = "/admin/logout",
                        method = FormMethod.post,
                        classes = "logout-form",
                    ) {
                        button(
                            type = ButtonType.submit,
                            classes = "btn-logout",
                        ) {
                            attributes["title"] = "Sign out"
                            inlineSvgIcon("logout", "Sign out")
                        }
                    }
                }
            }

            // ── Body: rail + ctx-panel + main ────────────────────────
            div("shell-body") {
                div("rail") {
                    div("rail-nav") {
                        val ws = workspaceSlug
                        railItem(
                            href = if (ws != null) "/admin/workspaces/$ws" else "/admin",
                            key = "apps",
                            activeKey = activeRail,
                            iconKey = "apps",
                        )
                        railItem(
                            href = if (ws != null) "/admin/workspaces/$ws/users" else "/admin/directory",
                            key = "directory",
                            activeKey = activeRail,
                            iconKey = "directory",
                        )
                        railItem(
                            href = if (ws != null) "/admin/workspaces/$ws/sessions" else "/admin/security",
                            key = "security",
                            activeKey = activeRail,
                            iconKey = "security",
                        )
                        railItem(
                            href = if (ws != null) "/admin/workspaces/$ws/logs" else "/admin/logs",
                            key = "logs",
                            activeKey = activeRail,
                            iconKey = "logs",
                        )
                        railItem(
                            href = if (ws != null) "/admin/workspaces/$ws/settings" else "/admin/settings",
                            key = "settings",
                            activeKey = activeRail,
                            iconKey = "settings",
                        )
                    }
                    div("rail-spacer") {}
                    a("/admin", classes = "rail-brand") {
                        img(src = "/static/brand/kotauth-negative-icon.svg", alt = "kotauth Brand") {}
                        p { +"v${appInfo.version}" }
                    }
                }

                if (workspaceSlug != null) {
                    div("ctx-panel") {
                        div("ctx-nav") {
                            when (activeRail) {
                                "apps" -> renderAppsCtxPanel(workspaceSlug, apps, activeAppSlug)
                                "directory" -> renderDirectoryCtxPanel(workspaceSlug, activeAppSection)
                                "security" -> renderSecurityCtxPanel(workspaceSlug, activeAppSection)
                                "logs" -> renderLogsCtxPanel(activeAppSection)
                                "settings" -> renderSettingsCtxPanel(workspaceSlug, activeAppSection)
                            }
                        }
                    }
                }

                div("main") {
                    div("content") {
                        content()
                    }
                }
            }
        }
    }
}

// ─── Rail Item ──────────────────────────────────────────────────────────────

private fun DIV.railItem(
    href: String,
    key: String,
    activeKey: String,
    iconKey: String,
) {
    val (iconName, label) =
        when (iconKey) {
            "apps" -> "rail-apps" to "Apps"
            "directory" -> "rail-directory" to "Directory"
            "security" -> "rail-security" to "Security"
            "logs" -> "rail-logs" to "Logs"
            "settings" -> "rail-settings" to "Settings"
            else -> iconKey to iconKey
        }
    a(href, classes = "rail-item${if (key == activeKey) " active" else ""}") {
        div("rail-icon") {
            inlineSvgIcon(iconName, label)
        }
        span("rail-item-label") { +label }
    }
}

// ─── Context Panel Sections ─────────────────────────────────────────────────

private fun DIV.ctxLink(
    href: String,
    key: String,
    activeKey: String,
    label: String,
) {
    a(href, classes = "ctx-item${if (key == activeKey) " active" else ""}") {
        span("ctx-item-label") { +label }
        if (key == activeKey) span("ctx-item-dot") {}
    }
}

internal fun DIV.renderAppsCtxPanel(
    workspaceSlug: String?,
    apps: List<Pair<String, String>>,
    activeAppSlug: String?,
) {
    span("ctx-section-title") { +"Applications" }
    if (apps.isEmpty()) {
        div("ctx-empty") {
            div("ctx-empty-icon") { +"⊡" }
            p("ctx-empty-text") { +"No applications yet." }
            a("/admin/workspaces/$workspaceSlug/applications/new", classes = "ctx-empty-action") {
                +"+ Create one"
            }
        }
    } else {
        apps.forEach { (appSlug, appName) ->
            a(
                href = "/admin/workspaces/$workspaceSlug/applications/$appSlug",
                classes = "ctx-item${if (appSlug == activeAppSlug) " active" else ""}",
            ) {
                span("ctx-item-label") { +appName }
            }
        }
    }
}

internal fun DIV.renderDirectoryCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("ctx-section-title") { +"Directory" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
    ctxLink("$base/users", "users", activeSection, "Users")
    ctxLink("$base/groups", "groups", activeSection, "Groups")
    ctxLink("$base/roles", "roles", activeSection, "Roles")
}

internal fun DIV.renderSecurityCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("ctx-section-title") { +"Security" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
    ctxLink("$base/mfa", "mfa", activeSection, "MFA")
    ctxLink("$base/sessions", "sessions", activeSection, "Sessions")
    ctxLink("$base/logs", "audit", activeSection, "Audit log")
}

internal fun DIV.renderLogsCtxPanel(activeSection: String) {
    span("ctx-section-title") { +"Logs" }
    ctxLink("/admin/logs/events", "events", activeSection, "Events")
    ctxLink("/admin/logs/errors", "errors", activeSection, "Errors")
}

internal fun DIV.renderSettingsCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("ctx-section-title") { +"Settings" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug/settings" else "/admin/settings"
    ctxLink(base, "general", activeSection, "General")
    ctxLink("$base/branding", "branding", activeSection, "Branding")
    ctxLink("$base/smtp", "smtp", activeSection, "SMTP")
    ctxLink("$base/security", "security", activeSection, "Security policy")
    ctxLink("$base/identity-providers", "identity-providers", activeSection, "Identity Providers")
    ctxLink("$base/api-keys", "api-keys", activeSection, "API Keys")
    ctxLink("$base/webhooks", "webhooks", activeSection, "Webhooks")
}
