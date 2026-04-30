package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * Per-workspace export view — `/admin/workspaces/{slug}/settings/backup`.
 *
 * One form, one submit, one downloaded file. Confirmation is the slug-typed-back
 * pattern (HTML5 `pattern` attribute, no JS) so the export button literally cannot
 * fire until the operator has typed the slug exactly. Combined with the password
 * + confirm-password pair this matches the strictest existing destructive flow
 * in the admin without any client-side script.
 */
internal fun workspaceBackupPageImpl(
    workspace: Tenant,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
): HTML.() -> Unit =
    {
        val slug = workspace.slug
        adminShell(
            pageTitle = "${EnglishStrings.BACKUP_PAGE_TITLE} — ${workspace.displayName}",
            activeRail = "settings",
            allWorkspaces = allWorkspaces,
            workspaceName = workspace.displayName,
            workspaceSlug = slug,
            workspaceLogoUrl = workspace.theme.logoUrl,
            loggedInAs = loggedInAs,
            activeAppSection = "backup",
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    slug to "/admin/workspaces/$slug",
                    "Settings" to "/admin/workspaces/$slug/settings",
                    EnglishStrings.BACKUP_NAV_LABEL to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") { +EnglishStrings.BACKUP_PAGE_TITLE }
                            p("page-header__sub") { +EnglishStrings.BACKUP_PAGE_SUBTITLE }
                        }
                    }
                    div("page-header__actions") {
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--primary")
                            attributes["form"] = "backup-export-form"
                            +EnglishStrings.BACKUP_DOWNLOAD_BUTTON
                        }
                    }
                }

                if (error != null) {
                    div("notice notice--error") { +error }
                }

                form(
                    action = "/admin/workspaces/$slug/settings/backup",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post,
                ) {
                    id = "backup-export-form"

                    // Passphrase + confirm
                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.BACKUP_PASSPHRASE_LABEL }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.BACKUP_PASSPHRASE_LABEL }
                            div {
                                input(type = InputType.password, name = "passphrase") {
                                    classes = setOf("edit-row__field")
                                    attributes["minlength"] = "16"
                                    attributes["autocomplete"] = "new-password"
                                    attributes["required"] = "required"
                                }
                                div("edit-row__hint") { +EnglishStrings.BACKUP_PASSPHRASE_HINT }
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.BACKUP_CONFIRM_PASSPHRASE_LABEL }
                            input(type = InputType.password, name = "confirmPassphrase") {
                                classes = setOf("edit-row__field")
                                attributes["minlength"] = "16"
                                attributes["autocomplete"] = "new-password"
                                attributes["required"] = "required"
                            }
                        }
                    }

                    // Optional includes
                    div("ov-card") {
                        div("ov-card__section-label") { +"Optional" }
                        label("check-row") {
                            input(type = InputType.checkBox, name = "includeSigningKeys") {
                                attributes["value"] = "true"
                            }
                            div("check-row__body") {
                                span("check-row__label") { +EnglishStrings.BACKUP_INCLUDE_SIGNING_KEYS_LABEL }
                                span("check-row__desc") { +EnglishStrings.BACKUP_INCLUDE_SIGNING_KEYS_DESC }
                            }
                        }
                        label("check-row") {
                            input(type = InputType.checkBox, name = "includeAuditLog") {
                                attributes["value"] = "true"
                            }
                            div("check-row__body") {
                                span("check-row__label") { +EnglishStrings.BACKUP_INCLUDE_AUDIT_LOG_LABEL }
                                span("check-row__desc") { +EnglishStrings.BACKUP_INCLUDE_AUDIT_LOG_DESC }
                            }
                        }
                    }

                    // Confirm-by-typing-slug gate
                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.BACKUP_CONFIRM_SLUG_LABEL }
                        div("edit-row") {
                            span("edit-row__label") { +"Workspace slug" }
                            div {
                                input(type = InputType.text, name = "confirmSlug") {
                                    classes = setOf("edit-row__field", "edit-row__field--mono")
                                    attributes["pattern"] = java.util.regex.Pattern.quote(slug)
                                    attributes["required"] = "required"
                                    attributes["autocomplete"] = "off"
                                    placeholder = slug
                                }
                                div("edit-row__hint") {
                                    +EnglishStrings.BACKUP_CONFIRM_SLUG_HINT_PREFIX
                                    span("edit-row__field--mono") { +slug }
                                    +EnglishStrings.BACKUP_CONFIRM_SLUG_HINT_SUFFIX
                                }
                            }
                        }
                    }

                    // Redaction notice — operator-facing list of what won't be in the export
                    div("ov-card") {
                        div("ov-card__section-label") { +EnglishStrings.BACKUP_REDACTED_HEADING }
                        p("edit-row__hint") { +EnglishStrings.BACKUP_REDACTED_INTRO }
                        ul {
                            li { +"OAuth client secrets — regenerate per application after import." }
                            li { +"Social provider client secrets — re-enter in Settings → Identity Providers." }
                            li { +"SMTP password — reconfigure in Settings → SMTP." }
                            li { +"MFA TOTP seeds and recovery codes — users must re-enroll after import." }
                            li { +"Active sessions, authorization codes, and magic-link tokens — never exported." }
                        }
                    }
                }
            }
        }
    }

/**
 * Top-level import view — `/admin/workspaces/import`.
 *
 * Multipart form: file picker for the encrypted envelope, a new slug, and the
 * matching passphrase. On success the route redirects to the new workspace's
 * detail page; on any failure the typed [BackupError] message is rendered above
 * the form so the operator can correct the input without losing what they typed.
 */
internal fun importTenantPageImpl(
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
    error: String? = null,
    prefillSlug: String? = null,
): HTML.() -> Unit =
    {
        adminShell(
            pageTitle = EnglishStrings.IMPORT_PAGE_TITLE,
            activeRail = "apps",
            allWorkspaces = allWorkspaces,
            loggedInAs = loggedInAs,
            contentClass = "content-outer",
        ) {
            div("content-inner") {
                breadcrumb(
                    "Workspaces" to "/admin",
                    EnglishStrings.IMPORT_PAGE_TITLE to null,
                )

                div("page-header") {
                    div("page-header__left") {
                        div("page-header__identity") {
                            h1("page-header__title") { +EnglishStrings.IMPORT_PAGE_TITLE }
                            p("page-header__sub") { +EnglishStrings.IMPORT_PAGE_SUBTITLE }
                        }
                    }
                    div("page-header__actions") {
                        button(type = ButtonType.submit) {
                            classes = setOf("btn", "btn--primary")
                            attributes["form"] = "backup-import-form"
                            +EnglishStrings.IMPORT_SUBMIT_BUTTON
                        }
                    }
                }

                if (error != null) {
                    div("notice notice--error") { +error }
                }

                form(
                    action = "/admin/workspaces/import",
                    encType = FormEncType.multipartFormData,
                    method = FormMethod.post,
                ) {
                    id = "backup-import-form"

                    div("ov-card") {
                        div("ov-card__section-label") { +"Source" }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.IMPORT_FILE_LABEL }
                            input(type = InputType.file, name = "envelope") {
                                classes = setOf("edit-row__field")
                                attributes["accept"] = ".enc,.json,application/octet-stream"
                                attributes["required"] = "required"
                            }
                        }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.IMPORT_PASSPHRASE_LABEL }
                            div {
                                input(type = InputType.password, name = "passphrase") {
                                    classes = setOf("edit-row__field")
                                    attributes["autocomplete"] = "off"
                                    attributes["required"] = "required"
                                }
                                div("edit-row__hint") { +EnglishStrings.IMPORT_PASSPHRASE_HINT }
                            }
                        }
                    }

                    div("ov-card") {
                        div("ov-card__section-label") { +"Destination" }
                        div("edit-row") {
                            span("edit-row__label") { +EnglishStrings.IMPORT_NEW_SLUG_LABEL }
                            div {
                                input(type = InputType.text, name = "newSlug") {
                                    classes = setOf("edit-row__field", "edit-row__field--mono")
                                    attributes["pattern"] = "[a-z0-9][a-z0-9-]{1,49}"
                                    attributes["required"] = "required"
                                    attributes["autocomplete"] = "off"
                                    placeholder = "acme-staging"
                                    if (!prefillSlug.isNullOrBlank()) value = prefillSlug
                                }
                                div("edit-row__hint") { +EnglishStrings.IMPORT_NEW_SLUG_HINT }
                            }
                        }
                    }
                }
            }
        }
    }
