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
        adminShell(
            pageTitle = "SMTP — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = workspace.slug,
            loggedInAs = loggedInAs,
            activeAppSection = "smtp",
        ) {
            div("breadcrumb") {
                a("/admin") { +"Workspaces" }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}") { +workspace.slug }
                span("breadcrumb-sep") { +"/" }
                a("/admin/workspaces/${workspace.slug}/settings") { +"Settings" }
                span("breadcrumb-sep") { +"/" }
                span("breadcrumb-current") { +"SMTP" }
            }
            div("page-header") {
                div {
                    p("page-title") { +"SMTP Settings" }
                    p("page-subtitle") {
                        +"Configure outbound email for ${workspace.displayName}. Used for verification and password reset emails."
                    }
                }
            }

            if (saved) {
                div("alert alert-success alert--constrained") {
                    +"SMTP settings saved."
                }
            }
            if (error != null) {
                div("alert alert-error alert--constrained") {
                    +error
                }
            }

            div("form-card") {
                form(
                    action = "/admin/workspaces/${workspace.slug}/settings/smtp",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    div("checkbox-row") {
                        input(type = InputType.checkBox, name = "smtpEnabled") {
                            id = "smtpEnabled"
                            if (workspace.smtpEnabled) checked = true
                            attributes["value"] = "true"
                        }
                        label("checkbox-label") {
                            htmlFor = "smtpEnabled"
                            +"Enable email delivery"
                        }
                    }

                    p("form-section-title") { +"Server" }
                    div("field") {
                        label {
                            htmlFor = "smtpHost"
                            +"Host"
                        }
                        input(type = InputType.text, name = "smtpHost") {
                            id = "smtpHost"
                            placeholder = "smtp.example.com"
                            value = workspace.smtpHost ?: ""
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "smtpPort"
                            +"Port"
                        }
                        input(type = InputType.number, name = "smtpPort") {
                            id = "smtpPort"
                            placeholder = "587"
                            attributes["min"] = "1"
                            attributes["max"] = "65535"
                            value = workspace.smtpPort.toString()
                        }
                        p("field-hint") { +"Common ports: 25 (SMTP), 465 (SMTPS), 587 (STARTTLS)." }
                    }
                    div("checkbox-row") {
                        input(type = InputType.checkBox, name = "smtpTlsEnabled") {
                            id = "smtpTlsEnabled"
                            if (workspace.smtpTlsEnabled) checked = true
                            attributes["value"] = "true"
                        }
                        label("checkbox-label") {
                            htmlFor = "smtpTlsEnabled"
                            +"Enable TLS / STARTTLS"
                        }
                    }

                    p("form-section-title") { +"Authentication" }
                    div("field") {
                        label {
                            htmlFor = "smtpUsername"
                            +"Username"
                        }
                        input(type = InputType.text, name = "smtpUsername") {
                            id = "smtpUsername"
                            placeholder = "user@example.com"
                            attributes["autocomplete"] = "off"
                            value = workspace.smtpUsername ?: ""
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "smtpPassword"
                            +"Password"
                        }
                        input(type = InputType.password, name = "smtpPassword") {
                            id = "smtpPassword"
                            attributes["autocomplete"] = "new-password"
                            // Never pre-fill — password is encrypted at rest
                        }
                        p("field-hint") {
                            if (workspace.smtpPassword != null) {
                                +"A password is already set. Leave blank to keep the existing password."
                            } else {
                                +"Enter the SMTP password. It is stored encrypted."
                            }
                        }
                    }

                    p("form-section-title") { +"Sender" }
                    div("field") {
                        label {
                            htmlFor = "smtpFromAddress"
                            +"From address"
                        }
                        input(type = InputType.email, name = "smtpFromAddress") {
                            id = "smtpFromAddress"
                            placeholder = "noreply@example.com"
                            value = workspace.smtpFromAddress ?: ""
                        }
                    }
                    div("field") {
                        label {
                            htmlFor = "smtpFromName"
                            +"From name (optional)"
                        }
                        input(type = InputType.text, name = "smtpFromName") {
                            id = "smtpFromName"
                            placeholder = "My App"
                            value = workspace.smtpFromName ?: ""
                        }
                    }

                    div("form-actions") {
                        button(type = ButtonType.submit, classes = "btn") { +"Save SMTP Settings" }
                        a(
                            "/admin/workspaces/${workspace.slug}/settings",
                            classes = "btn btn-ghost",
                        ) { +"Back to Settings" }
                    }
                }
            }
        }
    }
