package com.kauth.adapter.web.admin

import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.AuthMethodRow
import com.kauth.domain.model.MethodKey
import com.kauth.domain.model.Requirement
import com.kauth.domain.model.ProviderKey
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
                div("notice notice--warn") { +EnglishStrings.AUTH_METHODS_PASSWORD_OFF_WARNING }
            }
        }

        val socialRowCount = rows.count { it.key == MethodKey.SOCIAL_GOOGLE || it.key == MethodKey.SOCIAL_GITHUB }
        if (socialRowCount < ProviderKey.RESERVED.size) {
            p("methods-idp-hint") {
                a("/admin/workspaces/${tenant.slug}/settings/identity-providers") {
                    +EnglishStrings.ADMIN_METHODS_MORE_SIGN_IN_OPTIONS
                    inlineSvgIcon("arrow-small", "arrow")
                }
            }
        }
    }

    private fun TBODY.renderRow(row: AuthMethodRow, tenantSlug: String) {
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
}
