package com.kauth.adapter.web.portal

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dtf = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneOffset.UTC)

fun HTML.portalSessionsPage(
    tenant: Tenant,
    portalSession: PortalSession,
    sessions: List<Session>,
    ctx: ViewContext,
    layout: PortalLayout = PortalLayout.SIDEBAR,
    currentSessionId: Int? = null,
    successMsg: String? = null,
) {
    with(PortalView) {
        head { portalPageHead("${ctx.t("PORTAL_NAV_SESSIONS")} — ${ctx.workspaceName}", ctx.theme, layout) }
        body {
            if (successMsg != null) {
                attributes["data-toast-msg"] = EnglishStrings.TOAST_USER_SESSIONS_REVOKED
            }
            portalShell(tenant.slug, ctx, portalSession.username, "security/sessions", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("PORTAL_SECURITY_ACTIVE_SESSIONS") }
                    p(classes = "page-header__subtitle") { +ctx.t("PORTAL_SECURITY_SESSIONS_SUBTITLE") }
                }

                div(classes = "portal-section") {
                    div(classes = "portal-section__header") {
                        div(classes = "portal-section__header-left") {
                            span(classes = "portal-section__title") {
                                +ctx.t("PORTAL_SECURITY_ACTIVE_SESSIONS")
                            }
                            span(classes = "portal-section__subtitle") {
                                +ctx.t("PORTAL_SECURITY_SESSIONS_SUBTITLE")
                            }
                        }
                        if (sessions.size > 1) {
                            form(
                                action = "/t/${tenant.slug}/account/sessions/revoke-others",
                                method = FormMethod.post,
                            ) {
                                button(
                                    type = ButtonType.submit,
                                    classes = "btn btn--danger btn--sm",
                                ) {
                                    attributes["data-confirm"] =
                                        "Sign out of all other sessions? Only your current session will remain active."
                                    +ctx.t("PORTAL_SECURITY_REVOKE_OTHERS")
                                }
                            }
                        }
                    }
                    if (sessions.isEmpty()) {
                        div(classes = "portal-section__body") {
                            p(classes = "portal-empty") { +ctx.t("PORTAL_SECURITY_NO_SESSIONS") }
                        }
                    } else {
                        table(classes = "sessions-table") {
                            thead {
                                tr {
                                    th { +ctx.t("PORTAL_SECURITY_TABLE_DEVICE") }
                                    th { +ctx.t("PORTAL_SECURITY_TABLE_STARTED") }
                                    th { +ctx.t("PORTAL_SECURITY_TABLE_EXPIRES") }
                                    th { +"" }
                                }
                            }
                            tbody {
                                for (s in sessions) {
                                    val isCurrent = currentSessionId != null && s.id?.value == currentSessionId
                                    tr(classes = "portal-session-row") {
                                        td {
                                            div(classes = "session-device-label") {
                                                +UserAgentParser.parse(s.userAgent)
                                                if (isCurrent) {
                                                    span(classes = "session-current-pill") {
                                                        +ctx.t("PORTAL_SECURITY_CURRENT_PILL")
                                                    }
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
                                                    +ctx.t("PORTAL_SECURITY_REVOKE")
                                                }
                                            } else {
                                                form(
                                                    action = "/t/${tenant.slug}/account/sessions/${s.id?.value}/revoke",
                                                    method = FormMethod.post,
                                                ) {
                                                    button(
                                                        type = ButtonType.submit,
                                                        classes = "btn btn--danger btn--sm",
                                                    ) {
                                                        attributes["data-confirm"] =
                                                            "Revoke this session? The user will be signed out immediately."
                                                        +ctx.t("PORTAL_SECURITY_REVOKE")
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
    }
}
