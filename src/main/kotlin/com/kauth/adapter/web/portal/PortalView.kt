package com.kauth.adapter.web.portal

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.JsIntegrity
import com.kauth.adapter.web.ViewContext
import com.kauth.adapter.web.demoBanner
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.Application
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.SocialAccount
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.model.WebAuthnCredential
import kotlinx.html.*

sealed class PortalNavItem {
    data class Leaf(
        val activeKey: String,
        val labelKey: String,
        val href: (slug: String) -> String,
    ) : PortalNavItem()

    data class Group(
        val activeKeyPrefix: String,
        val labelKey: String,
        val children: List<Leaf>,
    ) : PortalNavItem()
}

/**
 * Self-service portal HTML views.
 *
 * Theme tokens (--color-accent, --color-text, etc.) are injected at runtime by
 * TenantTheme.toCssVars() before the portal CSS bundle is linked.
 *
 * Layout variants (selected per-tenant via PortalConfig.layout):
 *   SIDEBAR  — fixed 220px sidebar left, scrollable centered content right
 *   CENTERED — sticky topbar with horizontal tab strip, content below
 *
 * Login / MFA-challenge pages always use the auth card layout (kotauth-auth.css).
 */
object PortalView {
    private val portalNavItems: List<PortalNavItem> = listOf(
        PortalNavItem.Leaf(
            activeKey = "launcher",
            labelKey = "LAUNCHER_NAV",
            href = { slug -> "/t/$slug/launcher" },
        ),
        PortalNavItem.Leaf(
            activeKey = "profile",
            labelKey = "PORTAL_NAV_PROFILE",
            href = { slug -> "/t/$slug/account/profile" },
        ),
        PortalNavItem.Group(
            activeKeyPrefix = "security",
            labelKey = "PORTAL_NAV_SECURITY",
            children = listOf(
                PortalNavItem.Leaf(
                    activeKey = "security/overview",
                    labelKey = "PORTAL_NAV_SECURITY_OVERVIEW",
                    href = { slug -> "/t/$slug/account/security" },
                ),
                PortalNavItem.Leaf(
                    activeKey = "security/mfa",
                    labelKey = "PORTAL_NAV_MFA",
                    href = { slug -> "/t/$slug/account/mfa" },
                ),
                PortalNavItem.Leaf(
                    activeKey = "security/passkeys",
                    labelKey = "PORTAL_NAV_PASSKEYS",
                    href = { slug -> "/t/$slug/account/passkeys" },
                ),
                PortalNavItem.Leaf(
                    activeKey = "security/sessions",
                    labelKey = "PORTAL_NAV_SESSIONS",
                    href = { slug -> "/t/$slug/account/sessions" },
                ),
            ),
        ),
    )

    // =========================================================================
    // Portal login — matches AuthView card layout exactly
    // =========================================================================

    fun loginPage(
        slug: String,
        ctx: ViewContext,
        error: String?,
    ): HTML.() -> Unit =
        {
            head { authPageHead("${ctx.workspaceName} | ${ctx.t("PORTAL_LOGIN_SUBMIT")}", ctx.theme) }
            body {
                demoBanner()
                div("brand") {
                    div("brand-name") { +ctx.workspaceName }
                }
                div("card") {
                    h1("card-title") { +ctx.t("PORTAL_LOGIN_TITLE") }
                    p("card-subtitle") { +ctx.t("PORTAL_LOGIN_SUBTITLE") }

                    if (!error.isNullOrBlank()) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/t/$slug/account/login",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post,
                    ) {
                        div("field") {
                            label {
                                htmlFor = "username"
                                +ctx.t("PORTAL_LOGIN_USERNAME")
                            }
                            input(type = InputType.text, name = "username") {
                                id = "username"
                                placeholder = ctx.t("PORTAL_LOGIN_USERNAME_PLACEHOLDER")
                                attributes["autocomplete"] = "username"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        div("field") {
                            label {
                                htmlFor = "password"
                                +ctx.t("PORTAL_LOGIN_PASSWORD")
                            }
                            input(type = InputType.password, name = "password") {
                                id = "password"
                                placeholder = ctx.t("PORTAL_LOGIN_PASSWORD_PLACEHOLDER")
                                attributes["autocomplete"] = "current-password"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +ctx.t("PORTAL_LOGIN_SUBMIT") }
                    }

                    div("footer-link") {
                        a(href = "/t/$slug/forgot-password") { +ctx.t("PORTAL_LOGIN_FORGOT") }
                    }
                }
            }
        }

    // =========================================================================
    // Profile page
    // =========================================================================

    fun profilePage(
        slug: String,
        session: PortalSession,
        ctx: ViewContext,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        successMsg: String?,
        errorMsg: String?,
        email: String = "",
        fullName: String = "",
        connectedAccounts: List<SocialAccount> = emptyList(),
    ): HTML.() -> Unit =
        {
            head { portalPageHead("${ctx.t("PORTAL_PROFILE_TITLE")} — ${ctx.workspaceName}", ctx.theme, layout) }
            body {
                if (successMsg != null) {
                    attributes["data-toast-msg"] = EnglishStrings.TOAST_PROFILE_UPDATED
                }
                portalShell(slug, ctx, session.username, "profile", layout) {
                    div(classes = "page-header") {
                        h1(classes = "page-header__title") { +ctx.t("PORTAL_PROFILE_TITLE") }
                        p(classes = "page-header__subtitle") { +ctx.t("PORTAL_PROFILE_SUBTITLE") }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    div(classes = "portal-section") {
                        div(classes = "profile-identity") {
                            div(classes = "profile-identity__avatar") {
                                +(fullName.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                            }
                            div(classes = "profile-identity__info") {
                                div(classes = "profile-identity__name") { +fullName }
                                div(classes = "profile-identity__username") { +"@${session.username}" }
                            }
                        }
                        div(classes = "portal-section__body") {
                            form(
                                action = "/t/$slug/account/profile",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                                classes = "portal-form",
                            ) {
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "username"
                                        +ctx.t("PORTAL_PROFILE_USERNAME")
                                    }
                                    input(type = InputType.text, name = "username") {
                                        classes = setOf("edit-field__input", "edit-field__input--mono", "edit-field__input--disabled")
                                        id = "username"
                                        value = session.username
                                        disabled = true
                                        title = ctx.t("PORTAL_PROFILE_USERNAME_HINT")
                                    }
                                    p(classes = "edit-field__hint") { +ctx.t("PORTAL_PROFILE_USERNAME_HINT") }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "email"
                                        +ctx.t("PORTAL_PROFILE_EMAIL")
                                    }
                                    input(type = InputType.email, name = "email") {
                                        classes = setOf("edit-field__input")
                                        id = "email"
                                        value = email
                                        placeholder = "you@example.com"
                                        attributes["autocomplete"] = "email"
                                        required = true
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "full_name"
                                        +ctx.t("PORTAL_PROFILE_FULL_NAME")
                                    }
                                    input(type = InputType.text, name = "full_name") {
                                        classes = setOf("edit-field__input")
                                        id = "full_name"
                                        value = fullName
                                        placeholder = "Your full name"
                                        required = true
                                    }
                                }
                                div(classes = "edit-actions") {
                                    button(type = ButtonType.submit, classes = "btn btn--primary") {
                                        +ctx.t("PORTAL_PROFILE_SAVE")
                                    }
                                }
                            }
                        }
                    }

                    // ── Connected social accounts ───────────────────────
                    div(classes = "portal-section") {
                        attributes["aria-labelledby"] = "connected-accounts-title"
                        div(classes = "portal-section__header") {
                            div(classes = "portal-section__header-left") {
                                span(classes = "portal-section__title") {
                                    id = "connected-accounts-title"
                                    +ctx.t("CONNECTED_ACCOUNTS_TITLE")
                                }
                                span(classes = "portal-section__subtitle") {
                                    +ctx.t("CONNECTED_ACCOUNTS_SUBTITLE")
                                }
                            }
                        }
                        if (connectedAccounts.isEmpty()) {
                            div(classes = "portal-section__body") {
                                p(classes = "portal-empty") {
                                    +ctx.t("CONNECTED_ACCOUNTS_EMPTY")
                                }
                            }
                        } else {
                            ul(classes = "social-accounts-list") {
                                attributes["aria-label"] = ctx.t("CONNECTED_ACCOUNTS_SUBTITLE")
                                for (account in connectedAccounts) {
                                    li(classes = "social-accounts-list__item") {
                                        div(classes = "social-accounts-list__provider") {
                                            span(classes = "social-accounts-list__icon") {
                                                inlineSvgIcon(
                                                    iconName = "${account.provider.value}-logo",
                                                    ariaLabel = account.provider.displayName,
                                                )
                                            }
                                            div(classes = "social-accounts-list__info") {
                                                span(classes = "social-accounts-list__name") {
                                                    +account.provider.displayName
                                                }
                                                if (!account.providerEmail.isNullOrBlank()) {
                                                    span(classes = "social-accounts-list__email") {
                                                        +account.providerEmail
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    div(classes = "danger-zone") {
                        div(classes = "danger-zone__header") {
                            span(classes = "danger-zone__header-title") { +ctx.t("PORTAL_DANGER_ZONE") }
                        }
                        div(classes = "danger-zone__item") {
                            div(classes = "danger-zone__item-info") {
                                div(classes = "danger-zone__item-title") {
                                    +ctx.t("PORTAL_DELETE_ACCOUNT_TITLE")
                                }
                                div(classes = "danger-zone__item-desc") {
                                    +ctx.t("PORTAL_DELETE_ACCOUNT_DESC")
                                }
                            }
                            if (ctx.impersonation != null) {
                                span(classes = "tooltip-wrap") {
                                    attributes["data-tooltip"] = ctx.t("IMPERSONATION_DISABLED_TOOLTIP")
                                    button(
                                        type = ButtonType.button,
                                        classes = "btn btn--danger",
                                    ) {
                                        disabled = true
                                        +ctx.t("PORTAL_DELETE_ACCOUNT_BUTTON")
                                    }
                                }
                            } else {
                                button(
                                    type = ButtonType.button,
                                    classes = "btn btn--danger",
                                ) {
                                    attributes["data-action"] = "toggle-delete-confirm"
                                    +ctx.t("PORTAL_DELETE_ACCOUNT_BUTTON")
                                }
                            }
                        }
                        if (ctx.impersonation == null) {
                            div(classes = "confirm-block") {
                                id = "delete-confirm"
                                p(classes = "confirm-block__label") {
                                    +ctx.t("PORTAL_DELETE_CONFIRM_PREFIX")
                                    code { +session.username }
                                    +" to confirm deletion"
                                }
                                form(
                                    action = "/t/$slug/account/delete",
                                    method = FormMethod.post,
                                ) {
                                    div(classes = "confirm-block__row") {
                                        input(type = InputType.text, name = "confirm_username") {
                                            classes = setOf("confirm-block__input")
                                            placeholder = session.username
                                            required = true
                                            attributes["autocomplete"] = "off"
                                        }
                                        button(
                                            type = ButtonType.submit,
                                            classes = "btn btn--danger",
                                        ) { +ctx.t("PORTAL_DELETE_CONFIRM_BUTTON") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    // =========================================================================
    // Security page (change password only — sessions are on /account/sessions)
    // =========================================================================

    fun securityPage(
        slug: String,
        session: PortalSession,
        ctx: ViewContext,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        errorMsg: String?,
        passwordPolicy: SecurityConfig = SecurityConfig(),
    ): HTML.() -> Unit =
        {
            head { portalPageHead("${ctx.t("PORTAL_SECURITY_TITLE")} — ${ctx.workspaceName}", ctx.theme, layout) }
            body {
                portalShell(slug, ctx, session.username, "security/change-password", layout) {
                    div(classes = "page-header") {
                        h1(classes = "page-header__title") { +ctx.t("PORTAL_SECURITY_CHANGE_PASSWORD") }
                        p(classes = "page-header__subtitle") { +ctx.t("PORTAL_CHANGE_PASSWORD_SUBTITLE") }
                    }
                    if (!errorMsg.isNullOrBlank()) {
                        div(classes = "alert alert-error") { +errorMsg }
                    }

                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div(classes = "portal-section__header-left") {
                                span(classes = "portal-section__title") {
                                    +ctx.t("PORTAL_SECURITY_CHANGE_PASSWORD")
                                }
                            }
                        }
                        div(classes = "portal-section__body") {
                            form(
                                action = "/t/$slug/account/change-password",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                                classes = "portal-form",
                            ) {
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "current_password"
                                        +ctx.t("PORTAL_SECURITY_CURRENT_PASSWORD")
                                    }
                                    input(type = InputType.password, name = "current_password") {
                                        classes = setOf("edit-field__input")
                                        id = "current_password"
                                        required = true
                                        attributes["autocomplete"] = "current-password"
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "new_password"
                                        +EnglishStrings.NEW_PASSWORD
                                    }
                                    input(type = InputType.password, name = "new_password") {
                                        classes = setOf("edit-field__input")
                                        id = "new_password"
                                        placeholder = EnglishStrings.passwordMinPlaceholder(passwordPolicy.passwordMinLength)
                                        required = true
                                        attributes["autocomplete"] = "new-password"
                                        attributes["data-pw-min-length"] =
                                            passwordPolicy.passwordMinLength.toString()
                                        if (passwordPolicy.passwordRequireUppercase) {
                                            attributes["data-pw-require-upper"] = "true"
                                        }
                                        if (passwordPolicy.passwordRequireNumber) {
                                            attributes["data-pw-require-number"] = "true"
                                        }
                                        if (passwordPolicy.passwordRequireSpecial) {
                                            attributes["data-pw-require-special"] = "true"
                                        }
                                    }
                                }
                                div(classes = "edit-field") {
                                    label(classes = "edit-field__label") {
                                        htmlFor = "confirm_password"
                                        +EnglishStrings.CONFIRM_NEW_PASSWORD
                                    }
                                    input(type = InputType.password, name = "confirm_password") {
                                        classes = setOf("edit-field__input")
                                        id = "confirm_password"
                                        placeholder = EnglishStrings.CONFIRM_PASSWORD_PLACEHOLDER
                                        required = true
                                        attributes["autocomplete"] = "new-password"
                                        attributes["data-pw-mismatch-msg"] = EnglishStrings.PASSWORDS_DO_NOT_MATCH
                                    }
                                }
                                div(classes = "edit-actions") {
                                    span(classes = "edit-actions__note") {
                                        +ctx.t("PORTAL_SECURITY_SIGNOUT_NOTE")
                                    }
                                    if (ctx.impersonation != null) {
                                        span(classes = "tooltip-wrap") {
                                            attributes["data-tooltip"] =
                                                ctx.t("IMPERSONATION_DISABLED_TOOLTIP")
                                            button(type = ButtonType.submit, classes = "btn btn--primary") {
                                                disabled = true
                                                +ctx.t("PORTAL_SECURITY_CHANGE_PASSWORD")
                                            }
                                        }
                                    } else {
                                        button(type = ButtonType.submit, classes = "btn btn--primary") {
                                            +ctx.t("PORTAL_SECURITY_CHANGE_PASSWORD")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    // =========================================================================
    // App launcher page
    // =========================================================================

    fun launcherPage(
        slug: String,
        session: PortalSession,
        ctx: ViewContext,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        apps: List<Application>,
    ): HTML.() -> Unit = {
        head { portalPageHead("${ctx.t("LAUNCHER_PAGE_TITLE")} — ${ctx.workspaceName}", ctx.theme, layout) }
        body {
            portalShell(slug, ctx, session.username, "launcher", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("LAUNCHER_PAGE_TITLE") }
                    p(classes = "page-header__subtitle") { +ctx.t("LAUNCHER_PAGE_SUBTITLE") }
                }

                if (apps.isEmpty()) {
                    div(classes = "launcher-empty") {
                        div(classes = "launcher-empty__title") { +ctx.t("LAUNCHER_EMPTY_TITLE") }
                        div(classes = "launcher-empty__body") { +ctx.t("LAUNCHER_EMPTY_BODY") }
                    }
                } else {
                    div(classes = "launcher-grid") {
                        apps.forEach { app -> launcherTile(app, ctx) }
                    }
                }
            }
        }
    }

    private fun FlowContent.launcherTile(
        app: Application,
        ctx: ViewContext,
    ) {
        val launcherUrl = app.launcherUrl ?: return
        a(href = launcherUrl, classes = "launcher-tile") {
            target = "_blank"
            attributes["rel"] = "noopener noreferrer"
            attributes["aria-label"] = "${app.name} — ${ctx.t("LAUNCHER_OPEN_IN_NEW_TAB")}"
            div(classes = "launcher-tile__icon") {
                if (!app.iconUrl.isNullOrBlank()) {
                    img(src = app.iconUrl, alt = "") {
                        attributes["onerror"] =
                            "this.replaceWith(Object.assign(document.createElement('span')," +
                            "{textContent:this.dataset.fallback}))"
                        attributes["data-fallback"] =
                            (app.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                    }
                } else {
                    +(app.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
                }
            }
            div(classes = "launcher-tile__name") { +app.name }
        }
    }

    // =========================================================================
    // MFA management page
    //   mfaEnabled = false  →  setup flow (QR + recovery codes + verification)
    //   mfaEnabled = true   →  active state + disable option
    // =========================================================================

    fun mfaPage(
        slug: String,
        session: PortalSession,
        ctx: ViewContext,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        mfaEnabled: Boolean,
        successMsg: String? = null,
        errorMsg: String? = null,
        noticeMsg: String? = null,
    ): HTML.() -> Unit = {
        head {
            portalPageHead("${ctx.t("PORTAL_NAV_MFA")} — ${ctx.workspaceName}", ctx.theme, layout)
        }
        body {
            attributes["data-tenant-slug"] = slug
            if (successMsg != null) {
                attributes["data-toast-msg"] = EnglishStrings.TOAST_MFA_SETUP
            }
            portalShell(slug, ctx, session.username, "security/mfa", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("PORTAL_MFA_TITLE") }
                    p(classes = "page-header__subtitle") { +ctx.t("PORTAL_MFA_SUBTITLE") }
                }

                if (!noticeMsg.isNullOrBlank()) {
                    div(classes = "alert alert-warning") {
                        style = "font-size:14px; padding:12px 16px;"
                        +noticeMsg
                    }
                }
                if (!errorMsg.isNullOrBlank()) {
                    div(classes = "alert alert-error") { +errorMsg }
                }

                if (mfaEnabled) {
                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div {
                                div(classes = "portal-section__title") {
                                    +ctx.t("PORTAL_MFA_AUTHENTICATOR_APP")
                                }
                            }
                            span(classes = "badge badge--active") {
                                span(classes = "badge__dot") {}
                                +ctx.t("PORTAL_MFA_ACTIVE")
                            }
                        }
                        div(classes = "portal-section__body") {
                            p { +ctx.t("PORTAL_MFA_PROTECTING") }
                            p {
                                style = "margin-top:6px"
                                +ctx.t("PORTAL_MFA_SIGNIN_HINT")
                            }
                            p(classes = "form-hint") {
                                style = "margin-top:8px"
                                +ctx.t("PORTAL_MFA_RECOVERY_HINT")
                            }

                            div(classes = "divider") {}

                            div {
                                id = "disable-btn-row"
                                button(classes = "btn btn--danger") {
                                    attributes["data-action"] = "show-disable-confirm"
                                    +ctx.t("PORTAL_MFA_REMOVE")
                                }
                            }
                            div {
                                id = "disable-confirm"
                                style = "display:none"
                                div(classes = "alert-warning") {
                                    +ctx.t("PORTAL_MFA_REMOVE_WARNING")
                                }
                                div(classes = "mfa-action-row") {
                                    button(classes = "btn btn--danger") {
                                        id = "disable-btn"
                                        attributes["data-action"] = "disable-mfa"
                                        +ctx.t("PORTAL_MFA_REMOVE_CONFIRM")
                                    }
                                    button(classes = "btn btn--ghost") {
                                        attributes["data-action"] = "hide-disable-confirm"
                                        +ctx.t("PORTAL_MFA_CANCEL")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    div(classes = "portal-section") {
                        div(classes = "portal-section__header") {
                            div {
                                div(classes = "portal-section__title") {
                                    +ctx.t("PORTAL_MFA_AUTHENTICATOR_APP")
                                }
                            }
                            span(classes = "badge badge--warning") {
                                span(classes = "badge__dot") {}
                                +ctx.t("PORTAL_MFA_NOT_CONFIGURED")
                            }
                        }
                        div(classes = "portal-section__body") {
                            div {
                                id = "mfa-step-1"
                                p(classes = "form-hint") {
                                    +ctx.t("PORTAL_MFA_SETUP_INTRO")
                                }
                                div(classes = "compatible-apps") {
                                    p {
                                        +ctx.t("PORTAL_MFA_COMPATIBLE_APPS")
                                    }
                                    ul(classes = "compatible-apps__list") {
                                        span(classes = "compatible-apps__item") { +"Google Authenticator" }
                                        span(classes = "compatible-apps__item") { +"Authy" }
                                        span(classes = "compatible-apps__item") { +"Microsoft Authenticator" }
                                        span(classes = "compatible-apps__item") { +"1Password" }
                                        span(classes = "compatible-apps__item") { +"Bitwarden" }
                                    }
                                }
                                button(classes = "btn btn--primary") {
                                    id = "start-btn"
                                    attributes["data-action"] = "start-enrollment"
                                    +ctx.t("PORTAL_MFA_SETUP_BUTTON")
                                }
                                div(classes = "alert alert-error") {
                                    id = "enroll-error"
                                    style = "display:none;margin-top:14px"
                                }
                            }

                            div {
                                id = "mfa-step-2"
                                style = "display:none"

                                p(classes = "mfa-step-heading") { +"1. Scan this QR code" }
                                p(classes = "form-hint") {
                                    +ctx.t("PORTAL_MFA_SCAN_INSTRUCTION")
                                }
                                div(classes = "qr-container") { id = "qr-code" }
                                p(classes = "form-hint") {
                                    +ctx.t("PORTAL_MFA_MANUAL_KEY")
                                    span(classes = "mfa-secret-key") { id = "setup-key" }
                                }

                                div(classes = "divider") {}

                                p(classes = "mfa-step-heading") { +"2. Save your recovery codes" }
                                p {
                                    +ctx.t("PORTAL_MFA_RECOVERY_CODES_INTRO")
                                }
                                div(classes = "alert-warning") {
                                    +ctx.t("PORTAL_MFA_SAVE_CODES")
                                }
                                div(classes = "recovery-codes-grid") { id = "recovery-codes" }
                                button(classes = "btn btn--ghost") {
                                    id = "copy-codes-btn"
                                    style = "margin-bottom: 4px;"
                                    attributes["data-action"] = "copy-codes"
                                    +ctx.t("PORTAL_MFA_COPY_CODES")
                                }

                                label(classes = "mfa-confirm-label") {
                                    input(type = InputType.checkBox) {
                                        id = "codes-saved"
                                        attributes["data-action"] = "codes-saved-toggle"
                                    }
                                    +" I've saved my recovery codes in a safe place"
                                }

                                div {
                                    id = "mfa-step-2b"
                                    style = "display:none"
                                    div(classes = "divider") {}
                                    p(classes = "mfa-step-heading") { +"3. Verify your setup" }
                                    p {
                                        +ctx.t("PORTAL_MFA_VERIFY_INSTRUCTION")
                                    }
                                    div(classes = "edit-field") {
                                        label(classes = "edit-field__label") {
                                            htmlFor = "totp-code"
                                            +ctx.t("PORTAL_MFA_VERIFICATION_CODE")
                                        }
                                        input(type = InputType.text, name = "code") {
                                            classes = setOf("edit-field__input", "edit-field__input--mono")
                                            id = "totp-code"
                                            placeholder = "000000"
                                            attributes["inputmode"] = "numeric"
                                            attributes["pattern"] = "[0-9]*"
                                            maxLength = "6"
                                            attributes["autocomplete"] = "one-time-code"
                                        }
                                    }
                                    div(classes = "alert alert-error") {
                                        id = "verify-error"
                                        style = "display:none"
                                    }
                                    button(classes = "btn btn--primary") {
                                        id = "verify-btn"
                                        attributes["data-action"] = "verify-enrollment"
                                        +ctx.t("PORTAL_MFA_CONFIRM_SETUP")
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    // =========================================================================
    // Passkeys management page
    // =========================================================================

    fun passkeysPage(
        slug: String,
        session: PortalSession,
        ctx: ViewContext,
        layout: PortalLayout = PortalLayout.SIDEBAR,
        credentials: List<WebAuthnCredential>,
    ): HTML.() -> Unit = {
        head {
            portalPageHead("${ctx.t("PORTAL_NAV_PASSKEYS")} — ${ctx.workspaceName}", ctx.theme, layout)
        }
        body {
            attributes["data-tenant-slug"] = slug
            portalShell(slug, ctx, session.username, "security/passkeys", layout) {
                div(classes = "page-header") {
                    h1(classes = "page-header__title") { +ctx.t("PORTAL_PASSKEYS_TITLE") }
                    p(classes = "page-header__subtitle") { +ctx.t("PORTAL_PASSKEYS_INTRO") }
                }

                div(classes = "portal-section") {
                    div(classes = "portal-section__header") {
                        div(classes = "portal-section__title") { +ctx.t("PORTAL_PASSKEYS_TITLE") }
                        button(classes = "btn btn--sm") {
                            id = "add-passkey-btn"
                            +ctx.t("PORTAL_PASSKEYS_ADD_BUTTON")
                        }
                    }
                    div(classes = "portal-section__body") {
                        div(classes = "alert alert-error") {
                            id = "passkey-error"
                            attributes["hidden"] = "hidden"
                            attributes["role"] = "alert"
                        }
                        if (credentials.isEmpty()) {
                            p(classes = "form-hint") { +ctx.t("PORTAL_PASSKEYS_EMPTY_STATE") }
                        } else {
                            div(classes = "passkey-list") {
                                credentials.forEach { cred ->
                                    div(classes = "passkey-row") {
                                        attributes["data-passkey-id"] = (cred.id ?: 0).toString()
                                        div(classes = "passkey-row__info") {
                                            span { +cred.name }
                                            div(classes = "passkey-row__meta") {
                                                span {
                                                    +"${ctx.t("PORTAL_PASSKEYS_ADDED_ON")}: "
                                                    +cred.createdAt.toString().substring(0, 10)
                                                }
                                                if (cred.lastUsedAt != null) {
                                                    span { +" · " }
                                                    span {
                                                        +"${ctx.t("PORTAL_PASSKEYS_LAST_USED")}: "
                                                        +cred.lastUsedAt.toString().substring(0, 10)
                                                    }
                                                }
                                            }
                                        }
                                        div(classes = "passkey-row__actions") {
                                            button(classes = "btn btn--ghost btn--sm passkey-rename-btn") {
                                                attributes["data-passkey-name"] = cred.name
                                                attributes["data-passkey-id"] = (cred.id ?: 0).toString()
                                                +ctx.t("PORTAL_PASSKEYS_RENAME")
                                            }
                                            button(classes = "btn btn--danger btn--sm passkey-revoke-btn") {
                                                attributes["data-confirm"] =
                                                    ctx.t("PASSKEY_REMOVE_CONFIRM").replace("{name}", cred.name)
                                                attributes["data-passkey-id"] = (cred.id ?: 0).toString()
                                                +ctx.t("PORTAL_PASSKEYS_REVOKE")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                script(src = "/static/js/kotauth-passkeys.min.js?v=${AppInfo.assetVersion}") {
                    attributes["defer"] = "true"
                    JsIntegrity.passkeys?.let { attributes["integrity"] = it }
                    attributes["crossorigin"] = "anonymous"
                    attributes["data-passkey-base"] = "/t/$slug/passkeys"
                    attributes["data-passkey-mode"] = "manage"
                    attributes["data-passkey-add-title"] = ctx.t("PASSKEY_ADD_DIALOG_TITLE")
                    attributes["data-passkey-rename-title"] = ctx.t("PASSKEY_RENAME_DIALOG_TITLE")
                    attributes["data-passkey-error-generic"] = ctx.t("PASSKEY_ERROR_GENERIC")
                    attributes["data-passkey-error-cancelled"] = ctx.t("PASSKEY_ERROR_CANCELLED")
                    attributes["data-passkey-error-verification"] = ctx.t("PASSKEY_ERROR_VERIFICATION")
                    attributes["data-passkey-error-already-enrolled"] = ctx.t("PASSKEY_ERROR_ALREADY_ENROLLED")
                    attributes["data-passkey-error-unsupported"] = ctx.t("PASSKEY_ERROR_UNSUPPORTED")
                }
            }
        }
    }

    // =========================================================================
    // Shared <head> — login page (centered card, same as auth pages)
    // =========================================================================

    /**
     * Used only for the portal login page. Injects theme vars and links the auth
     * stylesheet so the card/field/btn classes work identically to the auth pages.
     */
    private fun HEAD.authPageHead(
        title: String,
        theme: TenantTheme,
    ) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
        // Favicon
        if (theme.faviconUrl != null) {
            link(rel = "icon", href = theme.faviconUrl)
        } else {
            link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
                attributes["sizes"] =
                    "32x32"
            }
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
                attributes["sizes"] =
                    "16x16"
            }
        }
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = theme.googleFontsUrl)
        style { unsafe { +theme.toCssVars() } }
        link(rel = "stylesheet", href = "/static/kotauth-auth.css?v=${AppInfo.assetVersion}")
    }

    // =========================================================================
    // Shared <head> — authenticated portal pages
    // =========================================================================

    internal fun HEAD.portalPageHead(
        title: String,
        theme: TenantTheme,
        layout: PortalLayout,
    ) {
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        title { +title }
        if (theme.faviconUrl != null) {
            link(rel = "icon", href = theme.faviconUrl)
        } else {
            link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-32x32.png") {
                attributes["sizes"] = "32x32"
            }
            link(rel = "icon", type = "image/png", href = "/static/favicon/favicon-16x16.png") {
                attributes["sizes"] = "16x16"
            }
        }
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = theme.googleFontsUrl)
        style { unsafe { +theme.toCssVars() } }
        val cssBundle = when (layout) {
            PortalLayout.SIDEBAR -> "/static/kotauth-portal-sidenav.css?v=${AppInfo.assetVersion}"
            PortalLayout.CENTERED -> "/static/kotauth-portal-tabnav.css?v=${AppInfo.assetVersion}"
        }
        link(rel = "stylesheet", href = cssBundle)
        script(src = "/static/js/kotauth-portal.min.js?v=${AppInfo.assetVersion}") {
            attributes["defer"] = "true"
            JsIntegrity.portal?.let { attributes["integrity"] = it }
            attributes["crossorigin"] = "anonymous"
        }
    }

    // =========================================================================
    // Shared layout — authenticated page shell (dispatches by layout)
    // =========================================================================

    internal fun BODY.portalShell(
        slug: String,
        ctx: ViewContext,
        username: String,
        activePage: String,
        layout: PortalLayout,
        content: DIV.() -> Unit,
    ) {
        demoBanner()
        impersonationBanner(slug, ctx)
        when (layout) {
            PortalLayout.SIDEBAR -> portalShellSidenav(slug, ctx, username, activePage, content)
            PortalLayout.CENTERED -> portalShellTabnav(slug, ctx, username, activePage, content)
        }

        dialog("confirm-dialog") {
            id = "confirm-dialog"
            div("confirm-dialog__card") {
                div("confirm-dialog__body") {
                    p("confirm-dialog__title") {
                        id = "confirm-dialog-title"
                        +ctx.t("PORTAL_CONFIRM_TITLE")
                    }
                    p("confirm-dialog__message") {
                        id = "confirm-dialog-message"
                    }
                }
                div("confirm-dialog__actions") {
                    button(classes = "btn btn--ghost") {
                        id = "confirm-dialog-cancel"
                        +ctx.t("PORTAL_CONFIRM_CANCEL")
                    }
                    button(classes = "btn btn--danger") {
                        id = "confirm-dialog-ok"
                        +ctx.t("PORTAL_CONFIRM_OK")
                    }
                }
            }
        }
        dialog(classes = "confirm-dialog") {
            id = "passkey-name-dialog"
            div("confirm-dialog__card") {
                div("confirm-dialog__body") {
                    p("confirm-dialog__title") {
                        id = "passkey-name-dialog-title"
                    }
                    input(type = InputType.text, classes = "edit-field__input") {
                        id = "passkey-name-dialog-input"
                        maxLength = "64"
                        required = true
                    }
                }
                div("confirm-dialog__actions") {
                    button(classes = "btn btn--ghost") {
                        id = "passkey-name-dialog-cancel"
                        +ctx.t("PASSKEY_CANCEL_BUTTON")
                    }
                    button(classes = "btn") {
                        id = "passkey-name-dialog-save"
                        +ctx.t("PASSKEY_SAVE_BUTTON")
                    }
                }
            }
        }
    }

    private fun BODY.portalShellSidenav(
        slug: String,
        ctx: ViewContext,
        username: String,
        activePage: String,
        content: DIV.() -> Unit,
    ) {
        val logoUrl = ctx.theme.logoUrl
        aside(classes = "portal-sidebar") {
            div(classes = "portal-sidebar__brand") {
                if (logoUrl != null) {
                    img(src = logoUrl, alt = ctx.workspaceName, classes = "portal-sidebar__brand-logo") {
                        width = "32"
                        height = "32"
                    }
                } else {
                    div(classes = "portal-sidebar__brand-mark") {
                        +workspaceInitials(ctx.workspaceName)
                    }
                }
                div(classes = "portal-sidebar__brand-info") {
                    span(classes = "portal-sidebar__org") { +ctx.workspaceName }
                    span(classes = "portal-sidebar__app") { +ctx.t("PORTAL_MY_ACCOUNT") }
                }
            }
            nav(classes = "portal-nav") {
                attributes["role"] = "navigation"
                attributes["aria-label"] = ctx.t("PORTAL_TOPBAR_TITLE")
                span(classes = "portal-nav__label") { +ctx.t("PORTAL_ACCOUNT") }
                portalNavTree(slug, ctx, activePage, "portal-nav__item")
            }
            div(classes = "portal-sidebar__footer") {
                portalUserMenu(slug, ctx, username, openUpward = true)
            }
        }
        div(classes = "portal-main-wrap") {
            div(classes = "portal-main") { content() }
        }
    }

    private fun BODY.portalShellTabnav(
        slug: String,
        ctx: ViewContext,
        username: String,
        activePage: String,
        content: DIV.() -> Unit,
    ) {
        val logoUrl = ctx.theme.logoUrl
        val initials = workspaceInitials(ctx.workspaceName)

        header(classes = "portal-topbar") {
            div(classes = "portal-topbar__inner") {
                div(classes = "portal-topbar__brand") {
                    if (logoUrl != null) {
                        img(src = logoUrl, alt = ctx.workspaceName, classes = "portal-topbar__brand-logo") {
                            width = "28"
                            height = "28"
                        }
                    } else {
                        div(classes = "portal-topbar__brand-mark") { +initials }
                    }
                    span(classes = "portal-topbar__org") { +ctx.workspaceName }
                }
                div(classes = "portal-topbar__spacer") {}
                portalUserMenu(slug, ctx, username, openUpward = false)
            }
        }
        nav(classes = "portal-tabnav") {
            attributes["role"] = "navigation"
            attributes["aria-label"] = ctx.t("PORTAL_TOPBAR_TITLE")
            div(classes = "portal-tabnav__inner") {
                portalNavTree(slug, ctx, activePage, "portal-tabnav__item")
            }
        }
        div(classes = "portal-content") { content() }
    }

    private fun FlowContent.portalNavTree(
        slug: String,
        ctx: ViewContext,
        activePage: String,
        linkClass: String,
    ) {
        for (item in portalNavItems) {
            when (item) {
                is PortalNavItem.Leaf -> {
                    a(
                        href = item.href(slug),
                        classes = "$linkClass${if (activePage == item.activeKey) " is-active" else ""}",
                    ) { +ctx.t(item.labelKey) }
                }
                is PortalNavItem.Group -> {
                    val groupActive = activePage.startsWith(item.activeKeyPrefix)
                    val groupClass = buildString {
                        append("portal-nav__group")
                        if (groupActive) append(" portal-nav__group--active")
                    }
                    div(classes = groupClass) {
                        span(classes = "$linkClass portal-nav__group-label") { +ctx.t(item.labelKey) }
                        div(classes = "portal-nav__children") {
                            for (child in item.children) {
                                a(
                                    href = child.href(slug),
                                    classes = "$linkClass portal-nav__child" +
                                        if (activePage == child.activeKey) " is-active" else "",
                                ) { +ctx.t(child.labelKey) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Shared helpers ────────────────────────────────────────────────────

    private fun workspaceInitials(workspaceName: String): String =
        workspaceName
            .split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "K" }

    /**
     * Sticky, full-width banner shown above the portal shell while the admin
     * is impersonating a user. No-op when [ViewContext.impersonation] is null.
     * The "End session" form posts to `/t/{slug}/account/impersonation/stop`.
     */
    private fun BODY.impersonationBanner(
        slug: String,
        ctx: ViewContext,
    ) {
        val info = ctx.impersonation ?: return
        div("impersonation-banner") {
            attributes["role"] = "status"
            div("impersonation-banner__inner") {
                div("impersonation-banner__copy") {
                    div("impersonation-banner__lead") {
                        +ctx.t("IMPERSONATION_BANNER_LEAD")
                        +" "
                        strong { +info.targetUsername }
                    }
                    div("impersonation-banner__sub") {
                        +ctx.t("IMPERSONATION_BANNER_SIGNED_IN_AS")
                        +" "
                        +info.adminUsername
                        +" — "
                        +ctx.t("IMPERSONATION_BANNER_AUDITED")
                    }
                }
                form(
                    action = "/t/$slug/account/impersonation/stop",
                    method = FormMethod.post,
                    classes = "impersonation-banner__form",
                ) {
                    button(type = ButtonType.submit, classes = "btn btn--ghost btn--sm") {
                        +ctx.t("IMPERSONATION_BANNER_END")
                    }
                }
            }
        }
    }

    private fun FlowContent.portalUserMenu(
        slug: String,
        ctx: ViewContext,
        username: String,
        openUpward: Boolean,
    ) {
        val signOut = ctx.t("PORTAL_SIGN_OUT")
        val avatarChar = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val rootClass =
            buildString {
                append("portal-user-menu")
                if (openUpward) append(" portal-user-menu--upward")
            }
        div(classes = rootClass) {
            attributes["data-user-menu"] = "true"
            button(
                type = ButtonType.button,
                classes = "portal-user-menu__trigger",
            ) {
                attributes["data-action"] = "toggle-user-menu"
                attributes["aria-haspopup"] = "menu"
                attributes["aria-expanded"] = "false"
                attributes["aria-label"] = ctx.t("PORTAL_USER_MENU_OPEN")
                div(classes = "portal-user-menu__avatar") {
                    attributes["aria-hidden"] = "true"
                    +avatarChar
                }
                div(classes = "portal-user-menu__info") {
                    span(classes = "portal-user-menu__name") { +username }
                    span(classes = "portal-user-menu__handle") { +"@$username" }
                }
                span(classes = "portal-user-menu__chevron") {
                    attributes["aria-hidden"] = "true"
                    inlineSvgIcon("chevron-down", "")
                }
            }
            div(classes = "portal-user-menu__panel") {
                attributes["role"] = "menu"
                attributes["hidden"] = "hidden"
                form(action = "/t/$slug/account/logout", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "portal-user-menu__item") {
                        attributes["role"] = "menuitem"
                        inlineSvgIcon("logout", signOut)
                        +signOut
                    }
                }
            }
        }
    }
}
