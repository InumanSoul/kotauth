package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.adapter.web.scim.ScimDialect
import com.kauth.adapter.web.scim.scimDialectFor
import com.kauth.adapter.web.scim.scimDialects
import com.kauth.domain.model.ApiKey
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.Tenant
import kotlinx.html.*

/**
 * Workspace provisioning page — the operator-facing surface for SCIM 2.0.
 *
 * The dialect selector and the per-provider notes are both derived from the registered
 * dialects, so a dialect added to the registry shows up here without a second list to update.
 * This page is also the one place an identity provider vendor is named: the names arrive as
 * copy on the dialect, never as a branch in this file.
 */
internal fun scimProvisioningPageImpl(
    workspace: Tenant,
    scimKeys: List<ApiKey>,
    endpointUrl: String,
    allWorkspaces: List<WorkspaceStub>,
    loggedInAs: String,
): HTML.() -> Unit = {
    val slug = workspace.slug
    val apiKeysHref = "/admin/workspaces/$slug/settings/api-keys"
    val createKeyHref = "$apiKeysHref/new?scope=${ApiScope.SCIM}"

    adminShell(
        pageTitle = "${EnglishStrings.SCIM_PAGE_TITLE} — ${workspace.displayName}",
        activeRail = "directory",
        allWorkspaces = allWorkspaces,
        workspaceName = workspace.displayName,
        workspaceSlug = slug,
        workspaceLogoUrl = workspace.theme.logoUrl,
        loggedInAs = loggedInAs,
        activeAppSection = "provisioning",
        contentClass = "content-outer",
    ) {
        div("content-inner") {
            breadcrumb(
                "Workspaces" to "/admin",
                slug to "/admin/workspaces/$slug",
                EnglishStrings.SCIM_PAGE_TITLE to null,
            )

            pageHeader(
                title = EnglishStrings.SCIM_PAGE_TITLE,
                subtitle = EnglishStrings.SCIM_PAGE_SUBTITLE,
                actions = { primaryLink(createKeyHref, EnglishStrings.SCIM_TOKEN_CREATE_CTA, "plus") },
            )

            // ── Endpoint ─────────────────────────────────────────────
            ovCard {
                ovSectionLabel(EnglishStrings.SCIM_ENDPOINT_HEADING)
                ovRowMono(EnglishStrings.SCIM_ENDPOINT_LABEL, endpointUrl, copyable = true)
                ovRowMuted("", EnglishStrings.SCIM_ENDPOINT_HINT)
            }

            // ── Status ───────────────────────────────────────────────
            ovCard {
                ovSectionLabel(EnglishStrings.SCIM_STATUS_HEADING)
                ovRowMuted(
                    "",
                    if (scimKeys.none { it.enabled }) {
                        EnglishStrings.SCIM_STATUS_NO_KEY
                    } else {
                        EnglishStrings.SCIM_STATUS_UNKNOWN
                    },
                )
            }

            // ── Token / keys ─────────────────────────────────────────
            ovCard {
                ovSectionLabel(EnglishStrings.SCIM_TOKEN_HEADING)
                ovRowMuted("", EnglishStrings.SCIM_TOKEN_HINT)
                if (scimKeys.isEmpty()) {
                    emptyState(
                        iconName = "code",
                        title = EnglishStrings.SCIM_KEYS_EMPTY_TITLE,
                        description = EnglishStrings.SCIM_KEYS_EMPTY_BODY,
                        cta = {
                            a(href = createKeyHref, classes = "empty-state__cta") {
                                inlineSvgIcon("plus", "New")
                                +EnglishStrings.SCIM_TOKEN_CREATE_CTA
                            }
                        },
                    )
                } else {
                    scimKeyTable(scimKeys)
                    div("ov-card__row") {
                        span("ov-card__value ov-card__value--muted") { +EnglishStrings.SCIM_STATUS_LAST_USE_HINT }
                    }
                }
                div("ov-card__row") {
                    a(apiKeysHref, classes = "btn btn--ghost") {
                        +EnglishStrings.SCIM_TOKEN_MANAGE_CTA
                        inlineSvgIcon("arrow-small", "arrow")
                    }
                }
            }

            // ── Behaviour ────────────────────────────────────────────
            ovCard {
                ovSectionLabel(EnglishStrings.SCIM_BEHAVIOUR_HEADING)
                notice(
                    title = EnglishStrings.SCIM_DEPROVISION_HEADING,
                    description = EnglishStrings.SCIM_DELETE_DEACTIVATES,
                    modifier = "notice--info",
                    iconName = "info",
                )
                ovRowMuted("", EnglishStrings.SCIM_BEHAVIOUR_GROUPS)
            }

            // ── Per-provider notes ───────────────────────────────────
            ovCard {
                ovSectionLabel(EnglishStrings.SCIM_NOTES_HEADING)
                ovRowMuted("", EnglishStrings.SCIM_NOTES_INTRO)
                // Only the first note of each dialect carries the label, so the rows read as one block.
                scimDialects.forEach { dialect ->
                    dialect.setupNotes.forEachIndexed { index, note ->
                        ovRowMuted(if (index == 0) dialect.label else "", note)
                    }
                }
            }
        }
    }
}

private fun DIV.scimKeyTable(scimKeys: List<ApiKey>) {
    table("key-table") {
        thead {
            tr {
                th { +EnglishStrings.SCIM_KEYS_COL_NAME }
                th { +EnglishStrings.SCIM_KEYS_COL_DIALECT }
                th { +EnglishStrings.SCIM_KEYS_COL_LAST_USED }
                th { +EnglishStrings.SCIM_KEYS_COL_STATE }
            }
        }
        tbody {
            scimKeys.forEach { key ->
                tr {
                    td {
                        span("key-table__name") { +key.name }
                        span("key-table__meta") { +" ${key.keyPrefix}…" }
                    }
                    td { span("key-table__meta") { +scimDialectFor(key.scimDialect).label } }
                    td {
                        span("key-table__meta") {
                            +(key.lastUsedAt?.toDisplayString() ?: EnglishStrings.SCIM_KEYS_NEVER_USED)
                        }
                    }
                    td {
                        val badgeCls = if (key.enabled) "badge badge--active" else "badge badge--inactive"
                        span(badgeCls) { +(if (key.enabled) "Active" else "Revoked") }
                    }
                }
            }
        }
    }
}

/** The dialect selector shared by the API key create form. Options come from the registry. */
internal fun DIV.scimDialectSelector(selectedId: String) {
    div("edit-row") {
        span("edit-row__label") { +EnglishStrings.SCIM_DIALECT_FIELD_LABEL }
        div {
            select {
                classes = setOf("edit-row__field")
                name = "scimDialect"
                attributes["form"] = "create-api-key-form"
                scimDialects.forEach { dialect: ScimDialect ->
                    option {
                        value = dialect.id
                        if (dialect.id == selectedId) selected = true
                        +dialect.label
                    }
                }
            }
            div("edit-row__hint") { +EnglishStrings.SCIM_DIALECT_FIELD_HINT }
        }
    }
}
