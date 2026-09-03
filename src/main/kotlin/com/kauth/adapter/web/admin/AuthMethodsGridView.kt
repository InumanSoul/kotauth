package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.AuthMethodRow
import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.Requirement
import com.kauth.domain.model.Tenant
import kotlinx.html.*

object AuthMethodsGridView {
    fun DIV.render(
        rows: List<AuthMethodRow>,
        tenant: Tenant,
    ) {
        div("ov-card") {
            div("ov-card__section-label") { +EnglishStrings.AUTH_METHODS_TABLE_HEADING }
            table("method-table") {
                thead {
                    tr {
                        th { +EnglishStrings.AUTH_METHODS_TABLE_COL_METHOD }
                        th { +EnglishStrings.AUTH_METHODS_TABLE_COL_ENABLED }
                        th { +EnglishStrings.AUTH_METHODS_TABLE_COL_NOTES }
                    }
                }
                tbody {
                    rows.forEach { row -> renderRow(row, tenant.slug) }
                }
            }
            val passwordRow = rows.firstOrNull { it.key == MethodKey.PASSWORD }
            if (passwordRow != null && !passwordRow.enabled) {
                notice(modifier = "notice--warn") {
                    span("notice__title") { +EnglishStrings.AUTH_METHODS_PASSWORD_OFF_WARNING }
                }
            }
        }

        // The aggregate row carries the same link, so the hint is only for a workspace that has
        // no brokered provider yet.
        if (rows.none { it.key == MethodKey.EXTERNAL_IDP }) {
            p("methods-idp-hint") {
                a(identityProvidersHref(tenant.slug)) {
                    +EnglishStrings.ADMIN_METHODS_MORE_SIGN_IN_OPTIONS
                    inlineSvgIcon("arrow-small", "arrow")
                }
            }
        }
    }

    private fun identityProvidersHref(tenantSlug: String) =
        "/admin/workspaces/$tenantSlug/settings/identity-providers"

    private fun TBODY.renderRow(row: AuthMethodRow, tenantSlug: String) {
        if (row.key == MethodKey.EXTERNAL_IDP) {
            renderExternalIdpRow(row, tenantSlug)
            return
        }
        tr {
            attributes["data-method-key"] = row.key.name
            td("method-table__method") {
                div("method-table__label") { +(EnglishStrings.byKey[row.labelKey] ?: row.labelKey) }
                if (row.descriptionKey != null) {
                    div("method-table__desc") { +(EnglishStrings.byKey[row.descriptionKey] ?: row.descriptionKey) }
                }
            }
            td("method-table__enabled") {
                if (row.toggleable) {
                    input(type = InputType.checkBox) {
                        name = "enabled_${row.key.name.lowercase()}"
                        checked = row.enabled
                    }
                } else {
                    val hasSmtpReq = row.requirements.any { it is Requirement.SmtpRequired }
                    if (hasSmtpReq) {
                        span("row-locked") {
                            +EnglishStrings.REQUIREMENT_SMTP_REQUIRED
                            +" — "
                            a(href = "/admin/workspaces/$tenantSlug/settings/smtp") {
                                +EnglishStrings.REQUIREMENT_SMTP_LINK
                            }
                        }
                    }
                }
            }
            td("method-table__notes") {
                row.requirements.forEach { req ->
                    span("badge badge--info") {
                        when (req) {
                            is Requirement.SmtpRequired ->
                                +EnglishStrings.REQUIREMENT_SMTP_REQUIRED
                            is Requirement.OAuthCredentialsRequired ->
                                +EnglishStrings.REQUIREMENT_OAUTH_CREDENTIALS_REQUIRED
                        }
                    }
                }
            }
        }
    }

    /**
     * The aggregate row: a count and the way to the page that owns these providers.
     *
     * It has no checkbox because nothing here can switch a brokered provider on — the row is a
     * pointer, and the service marks it non-toggleable so the POST never sees it either.
     */
    private fun TBODY.renderExternalIdpRow(row: AuthMethodRow, tenantSlug: String) {
        tr {
            attributes["data-method-key"] = row.key.name
            td("method-table__method") {
                div("method-table__label") { +(EnglishStrings.byKey[row.labelKey] ?: row.labelKey) }
                if (row.descriptionKey != null) {
                    div("method-table__desc") { +(EnglishStrings.byKey[row.descriptionKey] ?: row.descriptionKey) }
                }
            }
            td("method-table__enabled") {
                span("badge badge--info") {
                    +EnglishStrings.externalIdpConfiguredCount(row.aggregateCount ?: 0)
                }
            }
            td("method-table__notes") {
                if (!row.enabled) {
                    span("badge badge--warn") { +EnglishStrings.AUTH_METHODS_EXTERNAL_IDP_NONE_ENABLED }
                }
                a(identityProvidersHref(tenantSlug)) {
                    +EnglishStrings.AUTH_METHODS_EXTERNAL_IDP_MANAGE
                    inlineSvgIcon("arrow-small", "arrow")
                }
            }
        }
    }
}
