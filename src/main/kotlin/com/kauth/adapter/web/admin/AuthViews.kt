package com.kauth.adapter.web.admin

import kotlinx.html.*

// Standalone error page for OAuth callback errors (user is not authenticated).
internal fun adminOAuthErrorPageImpl(
    message: String,
    retryUrl: String,
): HTML.() -> Unit =
    {
        head { adminHead("Error") }
        body {
            div("login-shell") {
                div("brand") {
                    img(src = "/static/brand/kotauth-negative.svg", alt = "kotauth Brand") {}
                }
                div("login-card") {
                    h1("card-title") { +"Authentication Error" }
                    div("alert alert-error") { +message }
                    div {
                        style = "margin-top:1.5rem; text-align:center;"
                        a(retryUrl, classes = "btn btn--primary btn-full") { +"Try again" }
                    }
                }
                p("copyright") { +"© ${java.time.Year.now()} Powered by kotauth" }
            }
        }
    }

// Error page — rendered by the StatusPages error boundary.
internal fun adminErrorPageImpl(
    message: String,
    exceptionType: String? = null,
    allWorkspaces: List<WorkspaceStub> = emptyList(),
    loggedInAs: String = "—",
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Error — KotAuth",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
        ) {
            div("content-inner content-inner--wide") {
                pageHeader(
                    title = "Something went wrong",
                    subtitle = "An unexpected error occurred processing your request.",
                )

                div("alert alert-error alert--constrained") {
                    style = "margin-top:1.5rem;"
                    if (exceptionType != null) {
                        p {
                            style = "font-size:0.75rem; opacity:0.65; margin-bottom:0.35rem;"
                            +exceptionType
                        }
                    }
                    p {
                        style = "font-family:monospace; font-size:0.85rem; word-break:break-word;"
                        +message
                    }
                }
                div {
                    style = "margin-top:1.5rem;"
                    a("/admin", classes = "btn btn--ghost") { +"← Back to dashboard" }
                }
            }
        }
    }
