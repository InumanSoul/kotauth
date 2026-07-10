package com.kauth.adapter.web.portal

import com.kauth.adapter.web.ViewContext
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Tenant
import kotlinx.html.*

fun HTML.portalSecurityOverviewPage(
    tenant: Tenant,
    portalSession: PortalSession,
    ctx: ViewContext,
    layout: PortalLayout = PortalLayout.SIDEBAR,
) {
    with(PortalView) {
        head {
            portalPageHead("${ctx.t("PORTAL_NAV_SECURITY")} — ${ctx.workspaceName}", ctx.theme, layout)
        }
        body {
            portalShell(tenant.slug, ctx, portalSession.username, "security/overview", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("PORTAL_NAV_SECURITY") }
                    p(classes = "page-header__subtitle") { +ctx.t("PORTAL_SECURITY_SUBTITLE") }
                }

                div(classes = "portal-security-overview") {
                    div(classes = "ov-card") {
                        h2 { +ctx.t("PORTAL_NAV_MFA") }
                        p { +ctx.t("PORTAL_MFA_INTRO") }
                        a(
                            href = "/t/${tenant.slug}/account/mfa",
                            classes = "btn btn--outline",
                        ) { +ctx.t("PORTAL_SECURITY_MANAGE") }
                    }
                    div(classes = "ov-card") {
                        h2 { +ctx.t("PORTAL_NAV_PASSKEYS") }
                        p { +ctx.t("PORTAL_PASSKEYS_INTRO") }
                        a(
                            href = "/t/${tenant.slug}/account/passkeys",
                            classes = "btn btn--outline",
                        ) { +ctx.t("PORTAL_SECURITY_MANAGE") }
                    }
                    div(classes = "ov-card") {
                        h2 { +ctx.t("PORTAL_NAV_SESSIONS") }
                        p { +ctx.t("PORTAL_SESSIONS_INTRO") }
                        a(
                            href = "/t/${tenant.slug}/account/sessions",
                            classes = "btn btn--outline",
                        ) { +ctx.t("PORTAL_SECURITY_MANAGE") }
                    }
                }
            }
        }
    }
}
