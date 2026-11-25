package com.kauth.adapter.web.portal

import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Tenant
import com.kauth.domain.service.ConsentScope
import com.kauth.domain.service.ConsentedApplication
import kotlinx.html.*
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val approvedOn = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC)

/** One application the user allowed, with what it may access in the consent screen's words. */
data class AppAccessEntry(
    val grant: ConsentedApplication,
    val scopes: List<ConsentScope>,
)

fun HTML.portalAppAccessPage(
    tenant: Tenant,
    portalSession: PortalSession,
    entries: List<AppAccessEntry>,
    ctx: ViewContext,
    layout: PortalLayout = PortalLayout.SIDEBAR,
    revoked: Boolean = false,
) {
    with(PortalView) {
        head { portalPageHead("${ctx.t("PORTAL_NAV_APP_ACCESS")} — ${ctx.workspaceName}", ctx.theme, layout) }
        body {
            if (revoked) attributes["data-toast-msg"] = ctx.t("PORTAL_APP_ACCESS_REVOKED")
            portalShell(tenant.slug, ctx, portalSession.username, "security/apps", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("PORTAL_APP_ACCESS_TITLE") }
                    p(classes = "page-header__subtitle") { +ctx.t("PORTAL_APP_ACCESS_SUBTITLE") }
                }
                div(classes = "portal-section") {
                    if (entries.isEmpty()) {
                        div(classes = "portal-section__body") {
                            p(classes = "portal-empty") { +ctx.t("PORTAL_APP_ACCESS_EMPTY") }
                        }
                        return@div
                    }
                    table(classes = "sessions-table") {
                        thead {
                            tr {
                                th { +ctx.t("PORTAL_APP_ACCESS_COL_APP") }
                                th { +ctx.t("PORTAL_APP_ACCESS_COL_CAN") }
                                th { +"" }
                            }
                        }
                        tbody {
                                            entries.forEach { entry -> appAccessRow(tenant.slug, ctx, entry) }
                        }
                    }
                }
            }
        }
    }
}

private fun TBODY.appAccessRow(
    slug: String,
    ctx: ViewContext,
    entry: AppAccessEntry,
) {
    val app = entry.grant.application
    tr(classes = "portal-session-row") {
        td {
            div(classes = "session-device-label") { +app.name }
            span(classes = "session-time") {
                +ctx.t("PORTAL_APP_ACCESS_APPROVED", approvedOn.format(entry.grant.consent.updatedAt))
            }
        }
        td {
            entry.scopes.forEach { scope ->
                div(classes = "session-time") {
                    when {
                        scope.isIdentity -> +ctx.t("CONSENT_SCOPE_${scope.scope.uppercase()}")
                        scope.description != null -> +scope.description
                        else -> code { +scope.scope }
                    }
                }
            }
        }
        td {
            if (ctx.impersonation != null) {
                span(classes = "tooltip-wrap") {
                    attributes["data-tooltip"] = ctx.t("IMPERSONATION_DISABLED_TOOLTIP")
                    button(type = ButtonType.button, classes = "btn btn--danger btn--sm") {
                        disabled = true
                        +ctx.t("PORTAL_APP_ACCESS_REVOKE")
                    }
                }
            } else {
                form(action = "/t/$slug/account/apps/${app.id.value}/revoke", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "btn btn--danger btn--sm") {
                        attributes["data-confirm"] = ctx.t("PORTAL_APP_ACCESS_REVOKE_CONFIRM", app.name)
                        +ctx.t("PORTAL_APP_ACCESS_REVOKE")
                    }
                }
            }
        }
    }
}
