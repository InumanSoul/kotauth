package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.JsIntegrity
import com.kauth.adapter.web.demoBanner
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
    link(rel = "stylesheet", href = "/static/kotauth-admin.css?v=${AppInfo.assetVersion}")
    script(src = "/static/js/kotauth-admin.min.js?v=${AppInfo.assetVersion}") {
        attributes["defer"] = "true"
        JsIntegrity.admin?.let { attributes["integrity"] = it }
        attributes["crossorigin"] = "anonymous"
    }
    style {
        unsafe {
            +(
                "@import url('https://fonts.googleapis.com/css2?" +
                    "family=IBM+Plex+Sans:ital,wght@0,100..700;" +
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
 * @param showSidebar Whether to render the context sidebar (default true).
 *                    Set to false for single-page sections like Audit Log.
 * @param contentClass CSS class for the scrollable content wrapper.
 *                     Defaults to "content" (legacy). Use "content-outer"
 *                     for new BEM pages (wider padding, no sidebar).
 * @param content     Page content lambda
 */
internal fun HTML.adminShell(
    pageTitle: String,
    appInfo: AppInfo = AdminView.shellAppInfo,
    activeRail: String = "apps",
    allWorkspaces: List<WorkspaceStub> = emptyList(),
    workspaceName: String = "KotAuth",
    workspaceSlug: String? = null,
    workspaceLogoUrl: String? = null,
    apps: List<Pair<String, String>> = emptyList(),
    activeAppSlug: String? = null,
    activeAppSection: String = "overview",
    loggedInAs: String,
    showSidebar: Boolean = true,
    contentClass: String = "content",
    toastMessage: String? = null,
    content: DIV.() -> Unit,
) {
    head { adminHead(pageTitle) }
    body {
        attributes["hx-indicator"] = "#global-loader"
        if (toastMessage != null) {
            attributes["data-toast-msg"] = toastMessage
        }
        div("htmx-progress htmx-indicator") { id = "global-loader" }
        div {
            id = "toast-region"
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"
            attributes["aria-atomic"] = "true"
        }

        demoBanner()
        div("shell") {
            // ── Top bar ──────────────────────────────────────────────
            div("shell-topbar") {
                details("ws-dropdown") {
                    summary("ws-switcher") {
                        workspaceAvatar(workspaceName, workspaceLogoUrl, "ws-avatar ws-avatar--sm")
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
                            allWorkspaces.forEach { ws ->
                                val isActive = ws.slug == workspaceSlug
                                a(
                                    href = "/admin/workspaces/${ws.slug}",
                                    classes = "ws-dropdown-item${if (isActive) " active" else ""}",
                                ) {
                                    workspaceAvatar(ws.name, ws.logoUrl, "ws-avatar ws-avatar--sm")
                                    span("ws-dropdown-item-name") { +ws.name }
                                    if (isActive) span("ws-dropdown-item-check") { +"✓" }
                                }
                            }
                        }
                        div("ws-dropdown-divider") {}
                        a(
                            href = "/admin/workspaces/new",
                            classes = "ws-dropdown-item ws-dropdown-action",
                        ) { +"Add workspace" }
                        a(
                            href = "/admin/workspaces",
                            classes = "ws-dropdown-item ws-dropdown-action",
                        ) { +"Manage workspaces" }
                    }
                }

                div("topbar-search-wrap") {
                    if (workspaceSlug != null) {
                        form(
                            action = "/admin/workspaces/$workspaceSlug/users",
                            method = FormMethod.get,
                            classes = "topbar-search-form",
                        ) {
                            input(type = InputType.search, name = "q", classes = "topbar-search") {
                                placeholder = "Search users…"
                                attributes["autocomplete"] = "off"
                            }
                        }
                    } else {
                        input(type = InputType.search, classes = "topbar-search") {
                            placeholder = "Search users…"
                            disabled = true
                            attributes["title"] = "Select a workspace first"
                        }
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
                    val ws = workspaceSlug
                    railItem(
                        href = if (ws != null) "/admin/workspaces/$ws" else "/admin",
                        key = "apps",
                        activeKey = activeRail,
                        iconKey = "apps",
                    )
                    railItem(
                        href = ws?.let { "/admin/workspaces/$it/users" },
                        key = "directory",
                        activeKey = activeRail,
                        iconKey = "directory",
                    )
                    railItem(
                        href = ws?.let { "/admin/workspaces/$it/sessions" },
                        key = "security",
                        activeKey = activeRail,
                        iconKey = "security",
                    )
                    railItem(
                        href = ws?.let { "/admin/workspaces/$it/logs" },
                        key = "logs",
                        activeKey = activeRail,
                        iconKey = "logs",
                    )
                    railItem(
                        href = ws?.let { "/admin/workspaces/$it/settings" },
                        key = "settings",
                        activeKey = activeRail,
                        iconKey = "settings",
                    )
                    div("rail__spacer") {}
                    a("/admin", classes = "rail__brand") {
                        img(src = "/static/brand/kotauth-negative-icon.svg", alt = "kotauth Brand") {}
                        p(classes = "rail__version") { +"v${appInfo.version}" }
                    }
                }

                if (workspaceSlug != null && showSidebar) {
                    div("sidebar") {
                        when (activeRail) {
                            "apps" -> renderAppsCtxPanel(workspaceSlug, apps, activeAppSlug)
                            "directory" -> renderDirectoryCtxPanel(workspaceSlug, activeAppSection)
                            "security" -> renderSecurityCtxPanel(workspaceSlug, activeAppSection)
                            "logs" -> renderLogsCtxPanel(workspaceSlug, activeAppSection)
                            "settings" -> renderSettingsCtxPanel(workspaceSlug, activeAppSection)
                        }
                    }
                }

                div("main") {
                    div(contentClass) {
                        content()
                    }
                }
            }

            // Shared confirmation dialog — replaces browser confirm()
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
    }
}

// ─── Workspace Avatar ───────────────────────────────────────────────────────

internal fun FlowContent.workspaceAvatar(
    name: String,
    logoUrl: String?,
    cssClass: String = "ws-avatar",
) {
    if (!logoUrl.isNullOrBlank()) {
        img(src = logoUrl, alt = name, classes = "$cssClass ${cssClass.split(" ").first()}__img") {}
    } else {
        div(cssClass) {
            +(name.firstOrNull()?.uppercaseChar()?.toString() ?: "W")
        }
    }
}

// ─── Rail Item ──────────────────────────────────────────────────────────────

private fun DIV.railItem(
    href: String?,
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
    if (href != null) {
        a(href, classes = "rail__item${if (key == activeKey) " rail__item--active" else ""}") {
            attributes["title"] = label
            inlineSvgIcon(iconName, label)
            span("rail__label") { +label }
        }
    } else {
        span("rail__item rail__item--ghost") {
            attributes["title"] = "$label — select a workspace first"
            inlineSvgIcon(iconName, label)
            span("rail__label") { +label }
        }
    }
}

// ─── Context Panel Sections ─────────────────────────────────────────────────

private fun DIV.ctxLink(
    href: String,
    key: String,
    activeKey: String,
    label: String,
) {
    a(href, classes = "sidebar__item${if (key == activeKey) " sidebar__item--active" else ""}") {
        span { +label }
        if (key == activeKey) span("sidebar__dot") {}
    }
}

internal fun DIV.renderAppsCtxPanel(
    workspaceSlug: String?,
    apps: List<Pair<String, String>>,
    activeAppSlug: String?,
) {
    span("sidebar__heading") { +"Applications" }
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
                classes = "sidebar__item${if (appSlug == activeAppSlug) " sidebar__item--active" else ""}",
            ) {
                span { +appName }
                if (appSlug == activeAppSlug) span("sidebar__dot") {}
            }
        }
    }
}

internal fun DIV.renderDirectoryCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("sidebar__heading") { +"Directory" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
    ctxLink("$base/users", "users", activeSection, "Users")
    ctxLink("$base/groups", "groups", activeSection, "Groups")
    ctxLink("$base/roles", "roles", activeSection, "Roles")
}

internal fun DIV.renderSecurityCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("sidebar__heading") { +"Security" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
    ctxLink("$base/mfa", "mfa", activeSection, "MFA")
    ctxLink("$base/sessions", "sessions", activeSection, "Sessions")
}

internal fun DIV.renderLogsCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("sidebar__heading") { +"Logs" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug" else "/admin"
    ctxLink("$base/logs", "audit", activeSection, "Audit Log")
}

internal fun DIV.renderSettingsCtxPanel(
    workspaceSlug: String?,
    activeSection: String,
) {
    span("sidebar__heading") { +"Settings" }
    val base = if (workspaceSlug != null) "/admin/workspaces/$workspaceSlug/settings" else "/admin/settings"
    ctxLink(base, "general", activeSection, "General")
    ctxLink("$base/branding", "branding", activeSection, "Branding")
    ctxLink("$base/smtp", "smtp", activeSection, "SMTP")
    ctxLink("$base/security", "security", activeSection, "Security policy")
    ctxLink("$base/identity-providers", "identity-providers", activeSection, "Identity Providers")
    ctxLink("$base/api-keys", "api-keys", activeSection, "API Keys")
    ctxLink("$base/webhooks", "webhooks", activeSection, "Webhooks")
}
