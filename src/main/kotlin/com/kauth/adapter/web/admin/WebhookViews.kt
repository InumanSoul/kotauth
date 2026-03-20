package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.model.WebhookEvent
import kotlinx.html.*
import java.time.format.DateTimeFormatter

internal fun webhooksPageImpl(
    workspace: Tenant,
    endpoints: List<WebhookEndpoint>,
    deliveries: List<WebhookDelivery>,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    newSecret: String? = null,
    error: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = "Webhooks — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            loggedInAs = loggedInAs,
            activeAppSection = "webhooks",
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"Webhooks" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"Webhooks" }
                    p(
                        "page-subtitle",
                    ) {
                        +"Receive HTTP callbacks when security events occur. Payloads are signed with HMAC-SHA256."
                    }
                }
            }

            // One-time secret reveal
            if (newSecret != null) {
                div("alert alert-success") {
                    style = "max-width:720px; margin-bottom:1.5rem;"
                    p("alert__title") { +"Webhook created — copy the signing secret now. You will not see it again." }
                    div("secret-box") { +newSecret }
                    p {
                        style = "margin-top:0.5rem; font-size:0.8rem; color:var(--color-muted);"
                        +"Verify incoming payloads: "
                        code { +"X-KotAuth-Signature: sha256=HMAC-SHA256(secret, body)" }
                    }
                }
            }

            if (error != null) {
                div("alert alert-error") {
                    style = "max-width:720px;"
                    +error
                }
            }

            // Endpoints table
            div("card") {
                style = "max-width:960px; margin-bottom:2rem;"
                if (endpoints.isEmpty()) {
                    p("td-muted") {
                        style = "padding:1rem;"
                        +"No webhook endpoints yet. Add one below."
                    }
                } else {
                    table {
                        thead {
                            tr {
                                th { +"URL" }
                                th { +"Description" }
                                th { +"Events" }
                                th { +"Status" }
                                th { +"Created" }
                                th { +"" }
                            }
                        }
                        tbody {
                            endpoints.forEach { ep ->
                                tr {
                                    td {
                                        style =
                                            "font-size:0.82rem; font-family:monospace; max-width:280px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"
                                        +ep.url
                                    }
                                    td {
                                        span("td-muted") { +(ep.description.ifBlank { "—" }) }
                                    }
                                    td {
                                        style = "font-size:0.78rem; color:var(--color-muted); max-width:200px;"
                                        +(
                                            ep.events
                                                .sorted()
                                                .joinToString(", ")
                                                .ifBlank { "none" }
                                        )
                                    }
                                    td {
                                        span(if (ep.enabled) "badge badge-active" else "badge badge-disabled") {
                                            +(if (ep.enabled) "Enabled" else "Disabled")
                                        }
                                    }
                                    td {
                                        span("td-muted") {
                                            +DateTimeFormatter
                                                .ofPattern("MMM d, yyyy")
                                                .withZone(java.time.ZoneId.of("UTC"))
                                                .format(ep.createdAt)
                                        }
                                    }
                                    td("btn-group") {
                                        // Toggle enable/disable
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/settings/webhooks/${ep.id}/toggle",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            input(type = InputType.hidden, name = "enabled") {
                                                value = if (ep.enabled) "false" else "true"
                                            }
                                            button(type = ButtonType.submit, classes = "btn btn-ghost btn-sm") {
                                                +(if (ep.enabled) "Disable" else "Enable")
                                            }
                                        }
                                        // Delete
                                        form(
                                            action = "/admin/workspaces/${workspace.slug}/settings/webhooks/${ep.id}/delete",
                                            method = FormMethod.post,
                                            classes = "inline-form",
                                        ) {
                                            button(
                                                type = ButtonType.submit,
                                                classes = "btn btn-ghost btn-sm btn-danger",
                                            ) {
                                                attributes["onclick"] =
                                                    "return confirm('Delete this webhook endpoint? All delivery history will be lost.')"
                                                +"Delete"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Create endpoint form
            div("form-card form-card--wide") {
                style = "margin-bottom:2rem;"
                p("form-section-title") { +"Add Webhook Endpoint" }
                form(
                    action = "/admin/workspaces/${workspace.slug}/settings/webhooks",
                    method = FormMethod.post,
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                ) {
                    div("field") {
                        label {
                            htmlFor = "whUrl"
                            +"Target URL"
                        }
                        input(type = InputType.url, name = "url") {
                            id = "whUrl"
                            placeholder = "https://your-app.example.com/webhooks/kotauth"
                            required = true
                            maxLength = "2048"
                        }
                        p("field-hint") { +"KotAuth will POST signed JSON payloads to this URL." }
                    }
                    div("field") {
                        label {
                            htmlFor = "whDesc"
                            +"Description (optional)"
                        }
                        input(type = InputType.text, name = "description") {
                            id = "whDesc"
                            placeholder = "e.g. Slack alerts integration"
                            maxLength = "256"
                        }
                    }
                    div("field") {
                        label { +"Events" }
                        p("field-hint") {
                            style = "margin-bottom:0.5rem;"
                            +"Select the events this endpoint should receive."
                        }
                        div {
                            style = "display:grid; grid-template-columns:1fr 1fr; gap:0.5rem;"
                            WebhookEvent.ALL.forEach { event ->
                                label {
                                    style =
                                        "display:flex; align-items:center; gap:0.5rem; font-size:0.875rem; font-weight:400;"
                                    input(type = InputType.checkBox, name = "events") {
                                        value = event
                                        checked = true
                                    }
                                    span("td-code") {
                                        style = "font-size:0.8rem;"
                                        +event
                                    }
                                }
                            }
                        }
                    }
                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Add Endpoint" }
                    }
                }
            }

            // Recent delivery history
            if (deliveries.isNotEmpty()) {
                p("form-section-title") {
                    style = "max-width:960px;"
                    +"Recent Delivery History"
                }
                div("card") {
                    style = "max-width:960px;"
                    table {
                        thead {
                            tr {
                                th { +"Event" }
                                th { +"Endpoint" }
                                th { +"Status" }
                                th { +"HTTP" }
                                th { +"Attempts" }
                                th { +"Last attempt" }
                            }
                        }
                        tbody {
                            deliveries.take(50).forEach { d ->
                                val ep = endpoints.firstOrNull { it.id == d.endpointId }
                                tr {
                                    td {
                                        span("td-code") {
                                            style = "font-size:0.8rem;"
                                            +d.eventType
                                        }
                                    }
                                    td {
                                        style =
                                            "font-size:0.78rem; font-family:monospace; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;"
                                        +(ep?.url ?: "#${d.endpointId}")
                                    }
                                    td {
                                        span(
                                            when (d.status) {
                                                WebhookDeliveryStatus.DELIVERED -> "badge badge-active"
                                                WebhookDeliveryStatus.FAILED -> "badge badge-error"
                                                WebhookDeliveryStatus.PENDING -> "badge badge-pending"
                                            },
                                        ) {
                                            +d.status.value
                                        }
                                    }
                                    td { span("td-muted") { +(d.responseStatus?.toString() ?: "—") } }
                                    td { span("td-muted") { +d.attempts.toString() } }
                                    td {
                                        span("td-muted") {
                                            +(
                                                d.lastAttemptAt?.let {
                                                    DateTimeFormatter
                                                        .ofPattern("MMM d HH:mm")
                                                        .withZone(java.time.ZoneId.of("UTC"))
                                                        .format(it)
                                                } ?: "—"
                                            )
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
