package com.kauth.adapter.web.admin

import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.WebhookDelivery
import com.kauth.domain.model.WebhookDeliveryStatus
import com.kauth.domain.model.WebhookEndpoint
import com.kauth.domain.model.WebhookEvent
import kotlinx.html.*
import java.time.format.DateTimeFormatter

// ─── Webhooks List Page ─────────────────────────────────────────────────────

internal fun webhooksListPageImpl(
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

        adminShell(
            pageTitle = "Webhooks — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
            activeAppSection = "webhooks",
                  contentClass = "content-outer",
) {
            div("content-inner") {
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
                div("page-header__actions") {
                    primaryLink(
                        "/admin/workspaces/$slug/settings/webhooks/new",
                        "New Endpoint",
                        "plus",
                    )
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

            // ── Endpoints table / empty state ────────────────────────
            if (endpoints.isEmpty() && newSecret == null) {
                emptyState(
                    iconName = "pulse",
                    title = "No webhook endpoints yet",
                    description = "Add an endpoint to start receiving event deliveries.",
                    cta = {
                        a(
                            href = "/admin/workspaces/$slug/settings/webhooks/new",
                            classes = "empty-state__cta",
                        ) {
                            inlineSvgIcon("plus", "New")
                            +"Add Endpoint"
                        }
                    },
                )
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
                                    div("data-table__actions") {
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
            }

            // ── Recent delivery history ──────────────────────────────
            if (deliveries.isNotEmpty()) {
                div("ov-card") {
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
                            deliveries.forEach { d ->
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
}
    }

// ─── Create Webhook Endpoint Page ───────────────────────────────────────────

internal fun createWebhookPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        val totalEvents = WebhookEvent.ALL.size

        adminShell(
            pageTitle = "New Endpoint — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
            activeAppSection = "webhooks",
                    contentClass = "content-outer",
) {
            div("content-inner") {
            // ── Breadcrumb ───────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "Webhooks" to "/admin/workspaces/$slug/settings/webhooks",
                "New Endpoint" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"Add Webhook Endpoint" }
                        p("page-header__sub") {
                            +"KotAuth will POST signed JSON payloads to your endpoint."
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit, classes = "btn btn--primary") {
                        attributes["form"] = "create-webhook-form"
                        +"Add Endpoint"
                    }
                }
            }

            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Endpoint details ─────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Endpoint Details" }
                form(
                    action = "/admin/workspaces/$slug/settings/webhooks",
                    method = FormMethod.post,
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                ) {
                    id = "create-webhook-form"

                    div("edit-row") {
                        span("edit-row__label") { +"Target URL" }
                        div {
                            input(type = InputType.url, name = "url") {
                                classes = setOf("edit-row__field")
                                placeholder = "https://your-app.example.com/webhooks/kotauth"
                                required = true
                                maxLength = "2048"
                            }
                            div("edit-row__hint") { +"Must be HTTPS for production use." }
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
                }
            }

            // ── Events card ──────────────────────────────────────────
            div("ov-card") {
                div("ov-card__section-label") { +"Events" }
                div {
                    div("chip-grid__header") {
                        span("chip-grid__header-label") { +"Select which events trigger this webhook" }
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
                                    attributes["form"] = "create-webhook-form"
                                }
                                span("scope-chip__label") { +event }
                            }
                        }
                    }
                }
            }
                    }
}
    }
