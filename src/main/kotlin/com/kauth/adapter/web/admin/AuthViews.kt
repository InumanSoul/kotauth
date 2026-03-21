package com.kauth.adapter.web.admin

import com.kauth.adapter.web.demoBanner
import kotlinx.html.*

// Login page — standalone, no admin shell.
internal fun loginPageImpl(error: String? = null): HTML.() -> Unit =
    {
        head { adminHead("Login") }
        body {
            demoBanner()
            div("login-shell") {
                div("brand") {
                    img(src = "/static/brand/kotauth-negative.svg", alt = "kotauth Brand") {}
                }
                div("login-card") {
                    h1("card-title") { +"Administrator Login" }
                    p("card-subtitle") { +"Access is restricted to master workspace admins." }
                    if (error != null) {
                        div("alert alert-error") { +error }
                    }
                    form(
                        action = "/admin/login",
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
                                placeholder = "admin"
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
                                placeholder = "Enter password"
                                attributes["autocomplete"] = "current-password"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn btn--primary btn-full") { +"Sign In" }
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
    allWorkspaces: List<Pair<String, String>> = emptyList(),
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
