package com.kauth.adapter.web

import kotlinx.html.*

/**
 * View layer for the welcome / status page served at GET /.
 *
 * Pure function: data in → HTML out.
 * No HTTP context, no service calls, no side effects.
 *
 * In development mode the caller passes a populated [HealthInfo]; in production
 * it passes null and a minimal notice is rendered instead, so internal details
 * are never exposed at the root URL in live deployments.
 */
object WelcomeView {
    // -------------------------------------------------------------------------
    // Data shapes
    // -------------------------------------------------------------------------

    data class HealthInfo(
        val dbStatus: String,
        val dbLatencyMs: Long?,
        val dbDetail: String?,
        val configStatus: String,
        val configWarnings: List<String>,
    )

    // -------------------------------------------------------------------------
    // Page
    // -------------------------------------------------------------------------

    private fun statusPanelClass(health: HealthInfo): String {
        return when {
            health.dbStatus == "ok" && health.configStatus == "ok" -> "status-panel--ok"
            health.dbStatus == "degraded" || health.configStatus == "degraded" -> "status-panel--degraded"
            else -> "status-panel--critical"
        }
    }

    private fun HEAD.welcomeHead(
        pageTitle: String,
    ) {
        title { +pageTitle }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        // Favicon
        link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
        link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
            attributes["sizes"] =
                "32x32"
        }
        link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
            attributes["sizes"] =
                "16x16"
        }
        link(rel = "stylesheet", href = "/static/kotauth-admin.css")
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

    fun welcomePage(
        appInfo: AppInfo,
        uptimeSeconds: Long,
        health: HealthInfo?,
    ): HTML.() -> Unit = {
        lang = "en"
        head {
            welcomeHead(pageTitle = "Welcome to Kotauth")
        }
        body {
            demoBanner()
            div(classes = "welcome-shell") {
                div(classes = "welcome-content") {
                    // -- Header --------------------------------------------------
                    div(classes = "header") {
                        div(classes = "logo") {
                            img(src = "/static/brand/kotauth-negative.svg", alt = "kotauth logo") {}
                        }
                        span(classes = "badge badge-neutral") { +"v${appInfo.version}" }
                    }

                    // -- System status (development only) ------------------------
                    if (health != null) {
                        div(classes = "status-panel status-panel--visible ${statusPanelClass(health)}") {
                            p(classes = "status-panel__header") { +"System Status" }
                            div(classes = "status-panel__grid") {
                                // Database
                                div(classes = "status-item") {
                                    p(classes = "status-item__label") { +"Database" }
                                    if (health.dbStatus == "ok") {
                                        span(classes = "status-item__value") {
                                            span(classes = "status-dot status-dot--ok")
                                            +" Connected"
                                        }
                                        p(classes = "status-item__meta") { +"${health.dbLatencyMs}ms round-trip" }
                                    } else {
                                        span(classes = "status-item__value warn") {
                                            span(classes = "status-dot status-dot--critical")
                                            +" Unreachable"
                                        }
                                        p(classes = "status-item__meta") { +(health.dbDetail ?: "unknown error") }
                                    }
                                }
                                // Configuration
                                div(classes = "status-item") {
                                    p(classes = "status-item__label") { +"Configuration" }
                                    if (health.configStatus == "ok") {
                                        span(classes = "status-item__value") {
                                            span(classes = "status-dot status-dot--ok")
                                            +" Healthy"
                                        }
                                        p(classes = "status-item__meta") { +"All settings valid" }
                                    } else {
                                        span(classes = "status-item__value warn") {
                                            span(classes = "status-dot status-dot--degraded")
                                            +" Issues detected"
                                        }
                                        p(classes = "status-item__meta") {
                                            if (health.configWarnings.isNotEmpty()) {
                                                +"Warnings:"
                                                ul {
                                                    health.configWarnings.forEach { warning ->
                                                        li { +warning }
                                                    }
                                                }
                                            } else {
                                                +"Unknown configuration issues"
                                            }
                                        }

                                    }
                                }
                            }
                        }
                    }
                    // -- Quick links ---------------------------------------------
                    div(classes = "wc-card-grid") {
                        a(classes = "wc-card", href = "/admin"){
                            div(classes = "wc-card__top") {
                                div(classes = "wc-card__icon") {
                                    inlineSvgIcon(iconName = "admin", ariaLabel = "admin console icon")
                                }
                                inlineSvgIcon(iconName = "arrow-t-r", ariaLabel = "arrow icon", cssClass = "wc-card__arrow")
                            }
                            div(classes = "wc-card__title") { +"Admin Console" }
                            div(classes = "wc-card__desc") { +"Manage applications, users, scopes and OAuth clients through the visual interface." }
                        }
                        a(classes = "wc-card", href = "/api/docs"){
                            div(classes = "wc-card__top") {
                                div(classes = "wc-card__icon") {
                                    inlineSvgIcon(iconName = "code", ariaLabel = "api docs icon")
                                }
                                inlineSvgIcon(iconName = "arrow-t-r", ariaLabel = "arrow icon", cssClass = "wc-card__arrow")
                            }
                            div(classes = "wc-card__title") { +"API Docs" }
                            div(classes = "wc-card__desc") { +"Explore the REST API with interactive documentation, schemas and request examples." }
                        }
                        a(classes = "wc-card", href = "/health/ready"){
                            div(classes = "wc-card__top") {
                                div(classes = "wc-card__icon") {
                                    inlineSvgIcon(iconName = "pulse", ariaLabel = "health check icon")
                                }
                                inlineSvgIcon(iconName = "arrow-t-r", ariaLabel = "arrow icon", cssClass = "wc-card__arrow")
                            }
                            div(classes = "wc-card__title") { +"Health Check" }
                            div(classes = "wc-card__desc") { +"Inspect runtime health, database connectivity and configuration status as JSON." }
                        }
                        a(classes = "wc-card", href = "/t/master/.well-known/openid-configuration"){
                            div(classes = "wc-card__top") {
                                div(classes = "wc-card__icon") {
                                    inlineSvgIcon(iconName = "globe", ariaLabel = "oidc discovery icon")
                                }
                                inlineSvgIcon(iconName = "arrow-t-r", ariaLabel = "arrow icon", cssClass = "wc-card__arrow")
                            }
                            div(classes = "wc-card__title") { +"OIDC Discovery" }
                            div(classes = "wc-card__desc") { +"View the OpenID Connect discovery document, endpoint metadata and supported claims." }
                        }
                    }

                    // -- Footer --------------------------------------------------
                    div(classes = "ft-notes") {
                        val h = uptimeSeconds / 3600
                        val m = (uptimeSeconds % 3600) / 60
                        val s = uptimeSeconds % 60
                        +"uptime ${h}h ${m}m ${s}s"
                        span(classes = "sep") { +"·" }
                        +"Kotlin ${appInfo.kotlinVersion}"
                        span(classes = "sep") { +"·" }
                        +"Ktor ${appInfo.ktorVersion}"
                        span(classes = "sep") { +"·" }
                        +"JVM ${System.getProperty("java.version")}"
                    }
                }
            }
        }
    }
}
