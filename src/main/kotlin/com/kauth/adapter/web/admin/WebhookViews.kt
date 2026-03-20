package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
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
        val slug = workspace.slug
        val totalEvents = WebhookEvent.ALL.size

        adminShell(
            pageTitle = "Webhooks — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
            activeAppSection = "webhooks",
        ) {
            // ── Breadcrumb ───────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Webhooks" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Webhooks" }
                        p("page-header__sub") {
                            +"Receive HTTP callbacks when security events occur. Payloads signed with HMAC-SHA256."
                        }
                    }
                }
            }

            // ── One-time secret reveal ───────────────────────────────
            if (newSecret != null) {
                div("notice notice--success") {
                    p { +"Webhook created — copy the signing secret now. You will not see it again." }
                    div("copy-field") {
                        span("copy-field__value") { +newSecret }
                        button(type = ButtonType.button) {
                            classes = setOf("copy-field__btn")
                            attributes["data-copy"] = newSecret
                            title = "Copy"
                            inlineSvgIcon("copy", "Copy")
                        }
                    }
                    p("edit-row__hint") {
                        +"Verify incoming payloads: "
                        code { +"X-KotAuth-Signature: sha256=HMAC-SHA256(secret, body)" }
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Existing endpoints ───────────────────────────────────
            if (endpoints.isEmpty()) {
                div("empty-state") {
                    p("empty-state__title") { +"No webhook endpoints yet" }
                    p("empty-state__desc") { +"Add an endpoint below to start receiving event deliveries." }
                }
            } else {
                table("key-table") {
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
                                td { span("key-table__meta") { +ep.url } }
                                td { span("key-table__meta") { +(ep.description.ifBlank { "\u2014" }) } }
                                td {
                                    span("key-table__meta") {
                                        +(ep.events.sorted().joinToString(", ").ifBlank { "none" })
                                    }
                                }
                                td {
                                    val badgeCls = if (ep.enabled) "badge badge--active" else "badge badge--inactive"
                                    span(badgeCls) { +(if (ep.enabled) "Enabled" else "Disabled") }
                                }
                                td {
                                    span("key-table__meta") {
                                        +DateTimeFormatter
                                            .ofPattern("MMM d, yyyy")
                                            .withZone(java.time.ZoneId.of("UTC"))
                                            .format(ep.createdAt)
                                    }
                                }
                                td {
                                    // Toggle
                                    form(
                                        action = "/admin/workspaces/$slug/settings/webhooks/${ep.id}/toggle",
                                        method = FormMethod.post,
                                    ) {
                                        input(type = InputType.hidden, name = "enabled") {
                                            value = if (ep.enabled) "false" else "true"
                                        }
                                        button(type = ButtonType.submit) {
                                            classes = setOf("btn", "btn--ghost", "btn--sm")
                                            +(if (ep.enabled) "Disable" else "Enable")
                                        }
                                    }
                                    // Delete
                                    form(
                                        action = "/admin/workspaces/$slug/settings/webhooks/${ep.id}/delete",
                                        method = FormMethod.post,
                                    ) {
                                        button(type = ButtonType.submit) {
                                            classes = setOf("btn", "btn--ghost", "btn--sm", "btn--danger")
                                            attributes["data-confirm"] =
                                                "Delete this webhook endpoint? All delivery history will be lost."
                                            +"Delete"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Add endpoint form ────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Add Webhook Endpoint" }
                form(
                    action = "/admin/workspaces/$slug/settings/webhooks",
                    method = FormMethod.post,
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                ) {
                    div("edit-row") {
                        span("edit-row__label") { +"Target URL" }
                        div {
                            input(type = InputType.url, name = "url") {
                                classes = setOf("edit-row__field")
                                placeholder = "https://your-app.example.com/webhooks/kotauth"
                                required = true
                                maxLength = "2048"
                            }
                            div("edit-row__hint") { +"KotAuth will POST signed JSON payloads to this URL." }
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Description" }
                        input(type = InputType.text, name = "description") {
                            classes = setOf("edit-row__field")
                            placeholder = "e.g. Slack alerts integration"
                            maxLength = "256"
                        }
                    }

                    // ── Events chip grid ─────────────────────────────
                    div {
                        div("chip-grid__header") {
                            span("chip-grid__header-label") { +"Events" }
                            div("chip-grid__header-actions") {
                                span("chip-grid__count") {
                                    id = "events-count"
                                    +"0 / $totalEvents selected"
                                }
                                button(type = ButtonType.button) {
                                    classes = setOf("chip-grid__toggle")
                                    attributes["data-chips-all"] = "events-grid"
                                    +"All"
                                }
                                button(type = ButtonType.button) {
                                    classes = setOf("chip-grid__toggle")
                                    attributes["data-chips-none"] = "events-grid"
                                    +"None"
                                }
                            }
                        }
                        div("chip-grid") {
                            id = "events-grid"
                            WebhookEvent.ALL.forEach { event ->
                                label("scope-chip") {
                                    input(type = InputType.checkBox, name = "events") {
                                        value = event
                                    }
                                    span("scope-chip__label") { +event }
                                }
                            }
                        }
                    }

                    div("edit-actions") {
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--primary")
                            +"Add Endpoint"
                        }
                    }
                }
            }

            // ── Recent delivery history ──────────────────────────────
            if (deliveries.isNotEmpty()) {
                div("ov-card__section-label") { +"Recent Delivery History" }
                table("key-table") {
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
                                td { span("key-table__meta") { +d.eventType } }
                                td { span("key-table__meta") { +(ep?.url ?: "#${d.endpointId}") } }
                                td {
                                    span(
                                        when (d.status) {
                                            WebhookDeliveryStatus.DELIVERED -> "badge badge--active"
                                            WebhookDeliveryStatus.FAILED -> "badge badge--danger"
                                            WebhookDeliveryStatus.PENDING -> "badge badge--inactive"
                                        },
                                    ) {
                                        +d.status.value
                                    }
                                }
                                td { span("key-table__meta") { +(d.responseStatus?.toString() ?: "\u2014") } }
                                td { span("key-table__meta") { +d.attempts.toString() } }
                                td {
                                    span("key-table__meta") {
                                        +(
                                            d.lastAttemptAt?.let {
                                                DateTimeFormatter
                                                    .ofPattern("MMM d HH:mm")
                                                    .withZone(java.time.ZoneId.of("UTC"))
                                                    .format(it)
                                            } ?: "\u2014"
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
