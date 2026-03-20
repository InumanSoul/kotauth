package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * Per-workspace SMTP configuration page.
 *
 * Password field behaviour: when blank, the existing encrypted password is preserved.
 * The hint communicates this to the operator so they don't accidentally clear it.
 */
internal fun smtpSettingsPageImpl(
    workspace: Tenant,
    allWorkspaces: List<Pair<String, String>>,
    loggedInAs: String,
    error: String? = null,
    saved: Boolean = false,
): HTML.() -> Unit =
    {
        val slug = workspace.slug

        adminShell(
            pageTitle = "SMTP — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            loggedInAs = loggedInAs,
            activeAppSection = "smtp",
        ) {
            // ── Breadcrumb ───────────────────────────────────────────
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                "Settings" to "/admin/workspaces/$slug/settings",
                "SMTP" to null,
            )

            // ── Page header ──────────────────────────────────────────
            div("page-header") {
                div("page-header__left") {
                    div("page-header__identity") {
                        h1("page-header__title") { +"SMTP" }
                        p("page-header__sub") {
                            +"Configure outbound email for verification and password reset flows."
                        }
                    }
                }
                div("page-header__actions") {
                    button(type = ButtonType.submit) {
                        classes = setOf("btn", "btn--primary")
                        attributes["form"] = "smtp-form"
                        +"Save SMTP"
                    }
                }
            }

            // ── Notices ──────────────────────────────────────────────
            if (saved) {
                div("notice notice--success") { +"SMTP settings saved." }
            }
            if (error != null) {
                div("notice notice--error") { +error }
            }

            // ── Form (wraps all cards) ───────────────────────────────
            form(
                action = "/admin/workspaces/$slug/settings/smtp",
                encType = FormEncType.applicationXWwwFormUrlEncoded,
                method = FormMethod.post,
            ) {
                id = "smtp-form"

                // ── Enable toggle ────────────────────────────────────
                div("ov-card") {
                    div("toggle-row") {
                        div("toggle-row__body") {
                            div("toggle-row__title") { +"Enable email delivery" }
                            div("toggle-row__desc") {
                                +"When off, verification and reset emails will not be sent."
                            }
                        }
                        label("toggle") {
                            input(type = InputType.checkBox, name = "smtpEnabled") {
                                attributes["value"] = "true"
                                if (workspace.smtpEnabled) checked = true
                            }
                            span("toggle__track") { span("toggle__thumb") {} }
                        }
                    }
                }

                // ── Server ───────────────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Server" }
                    div("edit-row") {
                        span("edit-row__label") { +"Host" }
                        input(type = InputType.text, name = "smtpHost") {
                            classes = setOf("edit-row__field")
                            placeholder = "smtp.example.com"
                            value = workspace.smtpHost ?: ""
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Port" }
                        div {
                            input(type = InputType.number, name = "smtpPort") {
                                classes = setOf("edit-row__field", "edit-row__field--mono")
                                placeholder = "587"
                                attributes["min"] = "1"
                                attributes["max"] = "65535"
                                value = workspace.smtpPort.toString()
                            }
                            div("edit-row__hint") { +"Common: 25 (SMTP), 465 (SMTPS), 587 (STARTTLS)." }
                        }
                    }
                    div("toggle-row") {
                        div("toggle-row__body") {
                            div("toggle-row__title") { +"Enable TLS / STARTTLS" }
                        }
                        label("toggle") {
                            input(type = InputType.checkBox, name = "smtpTlsEnabled") {
                                attributes["value"] = "true"
                                if (workspace.smtpTlsEnabled) checked = true
                            }
                            span("toggle__track") { span("toggle__thumb") {} }
                        }
                    }
                }

                // ── Authentication ───────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Authentication" }
                    div("edit-row") {
                        span("edit-row__label") { +"Username" }
                        input(type = InputType.text, name = "smtpUsername") {
                            classes = setOf("edit-row__field")
                            placeholder = "user@example.com"
                            attributes["autocomplete"] = "off"
                            value = workspace.smtpUsername ?: ""
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"Password" }
                        div {
                            input(type = InputType.password, name = "smtpPassword") {
                                classes = setOf("edit-row__field")
                                placeholder = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                                attributes["autocomplete"] = "new-password"
                            }
                            div("edit-row__hint") {
                                if (workspace.smtpPassword != null) {
                                    +"Stored encrypted. Leave blank to keep existing password."
                                } else {
                                    +"Enter the SMTP password. It is stored encrypted."
                                }
                            }
                        }
                    }
                }

                // ── Sender ───────────────────────────────────────────
                div("ov-card") {
                    div("ov-card__section-label") { +"Sender" }
                    div("edit-row") {
                        span("edit-row__label") { +"From Address" }
                        input(type = InputType.email, name = "smtpFromAddress") {
                            classes = setOf("edit-row__field")
                            placeholder = "noreply@example.com"
                            value = workspace.smtpFromAddress ?: ""
                        }
                    }
                    div("edit-row") {
                        span("edit-row__label") { +"From Name" }
                        input(type = InputType.text, name = "smtpFromName") {
                            classes = setOf("edit-row__field")
                            placeholder = workspace.displayName
                            value = workspace.smtpFromName ?: ""
                        }
                    }
                }
            }
        }
    }
