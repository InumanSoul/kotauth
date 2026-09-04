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

internal fun adminErrorPageImpl(
    allWorkspaces: List<WorkspaceStub> = emptyList(),
    loggedInAs: String = "—",
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Error · KotAuth",
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            loggedInAs = loggedInAs,
        ) {
            div("content-inner content-inner--wide") {
                pageHeader(
                    title = "Something went wrong",
                    subtitle = "An unexpected error occurred. The details have been logged for the operator.",
                )

                div {
                    style = "margin-top:1.5rem;"
                    a("/admin", classes = "btn btn--ghost") { +"← Back to dashboard" }
                }
            }
        }
    }
