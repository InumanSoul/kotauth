package com.kauth.adapter.web.auth

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.EnglishStrings
import com.kauth.adapter.web.JsIntegrity
import com.kauth.adapter.web.ViewContext
import com.kauth.adapter.web.demoBanner
import com.kauth.adapter.web.inlineSvgIcon
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.LoginIdentifierMode
import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.JitRefusal
import kotlinx.html.*

/**
 * View layer for the auth module.
 *
 * Pure functions: data in → HTML out.
 * No HTTP context, no service calls, no side effects.
 *
 * Theming strategy:
 *   1. The tenant's TenantTheme is serialized to a :root { } CSS variable block.
 *   2. That block is injected as an inline <style> BEFORE the base stylesheet link.
 *   3. The base stylesheet (kotauth-auth.css) uses var(--token) throughout.
 *   Result: full white-label theming from the database, zero recompile.
 */
object AuthView {
    // -------------------------------------------------------------------------
    // Shared <head> builder
    // -------------------------------------------------------------------------

    private fun HEAD.authHead(
        pageTitle: String,
        theme: TenantTheme,
    ) {
        title { +pageTitle }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

        // Favicon — tenant custom URL takes precedence; fallback to bundled static assets
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

        // 1. Google Fonts for the tenant's chosen font family
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = theme.googleFontsUrl)

        // 2. Inject theme variables — base CSS reads from these
        style { unsafe { +theme.toCssVars() } }

        // 3. Base stylesheet that uses var(--token) exclusively
        link(rel = "stylesheet", href = "/static/kotauth-auth.css?v=${AppInfo.assetVersion}")

        script(src = "/static/js/kotauth-auth.min.js?v=${AppInfo.assetVersion}") {
            attributes["defer"] = "true"
            JsIntegrity.auth?.let { attributes["integrity"] = it }
            attributes["crossorigin"] = "anonymous"
        }
    }

    // -------------------------------------------------------------------------
    // Login page
    // -------------------------------------------------------------------------

    /*
     * loginPage params:
     * @param tenantSlug  Used to build form action URLs.
     * @param theme       Visual identity — injected as CSS variables.
     * @param error       Inline error message, or null for a clean form.
     * @param success     True when arriving from a successful registration.
     */

    /**
     * OAuth2 passthrough parameters — preserved across the login form submission
     * so the authorization endpoint can issue a code and redirect after login.
     */
    data class OAuthParams(
        val responseType: String? = null,
        val clientId: String? = null,
        val redirectUri: String? = null,
        val scope: String? = null,
        val state: String? = null,
        val codeChallenge: String? = null,
        val codeChallengeMethod: String? = null,
        val nonce: String? = null,
        val resources: List<String> = emptyList(),
    ) {
        val isOAuthFlow: Boolean get() = !responseType.isNullOrBlank()

        /** Serializes non-null OAuth params to a query string (with leading '?'). */
        fun toQueryString(): String {
            val parts = mutableListOf<String>()
            responseType?.let { parts += "response_type=$it" }
            clientId?.let { parts += "oauth_client_id=$it" }
            redirectUri?.let { parts += "redirect_uri=$it" }
            scope?.let { parts += "scope=$it" }
            state?.let { parts += "state=$it" }
            codeChallenge?.let { parts += "code_challenge=$it" }
            codeChallengeMethod?.let { parts += "code_challenge_method=$it" }
            nonce?.let { parts += "nonce=$it" }
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }
    }

    fun loginPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
        success: Boolean = false,
        oauthParams: OAuthParams = OAuthParams(),
        enabledProviders: List<LoginProvider> = emptyList(),
        registrationEnabled: Boolean = true,
        magicLinkEnabled: Boolean = false,
        passwordLoginEnabled: Boolean = true,
        emailOtpLoginEnabled: Boolean = false,
        passkeysEnabled: Boolean = false,
        loginIdentifierMode: LoginIdentifierMode = LoginIdentifierMode.USERNAME,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_LOGIN", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(ctx.workspaceName, ctx.theme) {
                    div("card") {
                        h1("card-title") { +ctx.t("LOGIN_WELCOME_BACK") }
                        p("card-subtitle") {
                            +ctx.t(if (passwordLoginEnabled) "LOGIN_SUBTITLE" else "LOGIN_PASSWORDLESS_SUBTITLE")
                        }

                        if (success) {
                            div("alert alert-success") {
                                +ctx.t("LOGIN_REGISTRATION_SUCCESS")
                            }
                        }
                        if (error != null) {
                            div("alert alert-error") { +error }
                        }

                        if (passwordLoginEnabled) {
                            form(
                                action = "/t/$tenantSlug/authorize",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                val identifierLabel =
                                    when (loginIdentifierMode) {
                                        LoginIdentifierMode.USERNAME -> ctx.t("LOGIN_USERNAME")
                                        LoginIdentifierMode.EMAIL -> ctx.t("LOGIN_IDENTIFIER_EMAIL")
                                        LoginIdentifierMode.EITHER -> ctx.t("LOGIN_IDENTIFIER_EITHER")
                                    }
                                val identifierPlaceholder =
                                    when (loginIdentifierMode) {
                                        LoginIdentifierMode.USERNAME -> ctx.t("LOGIN_USERNAME_PLACEHOLDER")
                                        LoginIdentifierMode.EMAIL -> ctx.t("LOGIN_IDENTIFIER_EMAIL_PLACEHOLDER")
                                        LoginIdentifierMode.EITHER -> ctx.t("LOGIN_IDENTIFIER_EITHER_PLACEHOLDER")
                                    }
                                // EITHER stays type=text: type=email would let native validation
                                // block a legitimate username from being submitted at all.
                                val identifierType =
                                    if (loginIdentifierMode == LoginIdentifierMode.EMAIL) {
                                        InputType.email
                                    } else {
                                        InputType.text
                                    }
                                val autocompleteBase =
                                    if (loginIdentifierMode == LoginIdentifierMode.EMAIL) "email" else "username"
                                div("field") {
                                    label {
                                        htmlFor = "username"
                                        +identifierLabel
                                    }
                                    input(type = identifierType, name = "username") {
                                        id = "username"
                                        placeholder = identifierPlaceholder
                                        attributes["autocomplete"] =
                                            if (passkeysEnabled) "$autocompleteBase webauthn" else autocompleteBase
                                        if (loginIdentifierMode == LoginIdentifierMode.EMAIL) {
                                            attributes["inputmode"] = "email"
                                        }
                                        required = true
                                        attributes["autofocus"] = "true"
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "password"
                                        +ctx.t("PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "password") {
                                            id = "password"
                                            placeholder = ctx.t("LOGIN_PASSWORD_PLACEHOLDER")
                                            attributes["autocomplete"] = "current-password"
                                            required = true
                                        }
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon(
                                                "eye-off",
                                                ctx.t("AUTH_ICON_HIDE"),
                                                cssClass = "icon-eye-off",
                                            )
                                        }
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") { +ctx.t("LOGIN_SUBMIT") }
                            }

                            if (passkeysEnabled) {
                                div("social-divider") {
                                    span { +ctx.t("LOGIN_OR_CONTINUE_WITH") }
                                }
                                div("alert alert-error") {
                                    id = "passkey-error"
                                    attributes["hidden"] = "hidden"
                                    attributes["role"] = "alert"
                                }
                                button(classes = "btn btn--ghost passkey-signin-btn") {
                                    +ctx.t("AUTH_LOGIN_PASSKEY_BUTTON")
                                }
                            }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/forgot-password") { +ctx.t("LOGIN_FORGOT_PASSWORD") }
                            }
                            if (magicLinkEnabled || emailOtpLoginEnabled) {
                                div("passwordless-options") {
                                    if (magicLinkEnabled) {
                                        div("footer-link") {
                                            a(href = "/t/$tenantSlug/magic-link") { +ctx.t("LOGIN_MAGIC_LINK_LINK") }
                                        }
                                    }
                                    if (emailOtpLoginEnabled) {
                                        div("footer-link") {
                                            a(href = "/t/$tenantSlug/email-otp") { +ctx.t("LOGIN_EMAIL_OTP_LINK") }
                                        }
                                    }
                                }
                            }
                        } else {
                            form(
                                action = "/t/$tenantSlug/magic-link/send",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                div("field") {
                                    label {
                                        htmlFor = "email"
                                        +ctx.t("LOGIN_PASSWORDLESS_EMAIL_LABEL")
                                    }
                                    input(type = InputType.email, name = "email") {
                                        id = "email"
                                        placeholder = ctx.t("LOGIN_PASSWORDLESS_EMAIL_PLACEHOLDER")
                                        attributes["autocomplete"] = "email"
                                        required = true
                                        attributes["autofocus"] = "true"
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") {
                                    +ctx.t("LOGIN_PASSWORDLESS_SUBMIT")
                                }
                            }
                            if (passkeysEnabled) {
                                div("alert alert-error") {
                                    id = "passkey-error"
                                    attributes["hidden"] = "hidden"
                                    attributes["role"] = "alert"
                                }
                                button(classes = "btn btn--ghost passkey-signin-btn") {
                                    +ctx.t("AUTH_LOGIN_PASSKEY_BUTTON")
                                }
                            }
                            if (emailOtpLoginEnabled) {
                                div("footer-link") {
                                    a(href = "/t/$tenantSlug/email-otp") { +ctx.t("LOGIN_EMAIL_OTP_LINK") }
                                }
                            }
                        }

                        if (registrationEnabled) {
                            div("footer-link") {
                                +ctx.t("LOGIN_NO_ACCOUNT")
                                a(href = "/t/$tenantSlug/register") { +ctx.t("LOGIN_CREATE_ONE") }
                            }
                        }

                        // Social login buttons — only shown when providers are configured.
                        // Drop the "or continue with" divider in passwordless mode: magic-link
                        // and social are co-equal primaries, not a fallback to a password form.
                        if (enabledProviders.isNotEmpty()) {
                            if (passwordLoginEnabled) {
                                div("social-divider") {
                                    span { +ctx.t("LOGIN_OR_CONTINUE_WITH") }
                                }
                            }
                            div("social-buttons") {
                                for (prov in enabledProviders) {
                                    val qs = oauthParams.toQueryString()
                                    a(
                                        href = "/t/$tenantSlug/auth/social/${prov.key.value}/redirect$qs",
                                        classes = "btn-social",
                                    ) {
                                        socialProviderButton(prov, ctx)
                                    }
                                }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }

                if (passkeysEnabled) {
                    script(src = "/static/js/kotauth-passkeys.min.js?v=${AppInfo.assetVersion}") {
                        JsIntegrity.passkeys?.let { attributes["integrity"] = it }
                        attributes["crossorigin"] = "anonymous"
                        attributes["data-passkey-base"] = "/t/$tenantSlug/passkeys"
                        attributes["data-passkey-mode"] = "login"
                        attributes["data-passkey-error-generic"] = ctx.t("PASSKEY_ERROR_GENERIC")
                        attributes["data-passkey-error-cancelled"] = ctx.t("PASSKEY_ERROR_CANCELLED")
                        attributes["data-passkey-error-verification"] = ctx.t("PASSKEY_ERROR_VERIFICATION")
                        attributes["data-passkey-error-already-enrolled"] = ctx.t("PASSKEY_ERROR_ALREADY_ENROLLED")
                        attributes["data-passkey-error-unsupported"] = ctx.t("PASSKEY_ERROR_UNSUPPORTED")
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Registration page
    // -------------------------------------------------------------------------

    fun registerPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
        prefill: RegisterPrefill = RegisterPrefill(),
        enabledProviders: List<LoginProvider> = emptyList(),
        passwordPolicy: SecurityConfig = SecurityConfig(),
        passwordLoginEnabled: Boolean = true,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_REGISTER", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(ctx.workspaceName, ctx.theme) {
                    div("card") {
                        h1("card-title") { +ctx.t("REGISTER_TITLE") }
                        p("card-subtitle") {
                            +ctx.t(if (passwordLoginEnabled) "REGISTER_SUBTITLE" else "REGISTER_PASSWORDLESS_SUBTITLE")
                        }

                        if (error != null) {
                            div("alert alert-error") { +error }
                        }

                        form(
                            action = "/t/$tenantSlug/register",
                            encType = FormEncType.applicationXWwwFormUrlEncoded,
                            method = FormMethod.post,
                        ) {
                            div("field") {
                                label {
                                    htmlFor = "fullName"
                                    +ctx.t("REGISTER_FULL_NAME")
                                }
                                input(type = InputType.text, name = "fullName") {
                                    id = "fullName"
                                    placeholder = ctx.t("REGISTER_FULL_NAME_PLACEHOLDER")
                                    attributes["autocomplete"] = "name"
                                    value = prefill.fullName
                                    required = true
                                }
                            }
                            div("field") {
                                label {
                                    htmlFor = "email"
                                    +ctx.t("REGISTER_EMAIL")
                                }
                                input(type = InputType.email, name = "email") {
                                    id = "email"
                                    placeholder = ctx.t("REGISTER_EMAIL_PLACEHOLDER")
                                    attributes["autocomplete"] = "email"
                                    value = prefill.email
                                    required = true
                                }
                            }
                            div("field") {
                                label {
                                    htmlFor = "username"
                                    +ctx.t("REGISTER_USERNAME")
                                }
                                input(type = InputType.text, name = "username") {
                                    id = "username"
                                    placeholder = ctx.t("REGISTER_USERNAME_PLACEHOLDER")
                                    attributes["autocomplete"] = "username"
                                    value = prefill.username
                                    required = true
                                }
                            }
                            if (passwordLoginEnabled) {
                                div("divider") {}
                                div("field") {
                                    label {
                                        htmlFor = "password"
                                        +ctx.t("PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "password") {
                                            id = "password"
                                            placeholder =
                                                ctx.t(
                                                    "PASSWORD_MIN_PLACEHOLDER",
                                                    passwordPolicy.passwordMinLength,
                                                )
                                            attributes["autocomplete"] = "new-password"
                                            required = true
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
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "confirmPassword"
                                        +ctx.t("CONFIRM_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "confirmPassword") {
                                            id = "confirmPassword"
                                            placeholder = ctx.t("CONFIRM_PASSWORD_PLACEHOLDER")
                                            attributes["autocomplete"] = "new-password"
                                            attributes["data-pw-mismatch-msg"] = ctx.t("PASSWORDS_DO_NOT_MATCH")
                                            required = true
                                        }
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "confirmPassword"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn") { +ctx.t("REGISTER_SUBMIT") }
                        }

                        div("footer-link") {
                            +ctx.t("REGISTER_HAS_ACCOUNT")
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("REGISTER_SIGN_IN") }
                        }

                        // Social login buttons — same providers as the login page.
                        // Clicking one initiates the OAuth flow; if the account doesn't exist
                        // yet the callback redirects to complete-registration automatically.
                        if (enabledProviders.isNotEmpty()) {
                            div("social-divider") {
                                span { +ctx.t("REGISTER_OR_SIGN_UP_WITH") }
                            }
                            div("social-buttons") {
                                for (prov in enabledProviders) {
                                    a(
                                        href = "/t/$tenantSlug/auth/social/${prov.key.value}/redirect",
                                        classes = "btn-social",
                                    ) {
                                        socialProviderButton(prov, ctx)
                                    }
                                }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Forgot password — request a reset link
    // -------------------------------------------------------------------------

    /**
     * @param sent  True after the form has been submitted — shows the generic
     *              "if an account exists…" message to prevent email enumeration.
     */
    fun forgotPasswordPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
        sent: Boolean = false,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_FORGOT", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(ctx.workspaceName, ctx.theme) {
                    div("card") {
                        h1("card-title") { +ctx.t("FORGOT_TITLE") }

                        if (sent) {
                            p("card-subtitle") { +ctx.t("FORGOT_SENT_MESSAGE") }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                            }
                        } else {
                            p("card-subtitle") { +ctx.t("FORGOT_SUBTITLE") }

                            if (error != null) {
                                div("alert alert-error") { +error }
                            }

                            form(
                                action = "/t/$tenantSlug/forgot-password",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                div("field") {
                                    label {
                                        htmlFor = "email"
                                        +ctx.t("FORGOT_EMAIL_LABEL")
                                    }
                                    input(type = InputType.email, name = "email") {
                                        id = "email"
                                        placeholder = ctx.t("FORGOT_EMAIL_PLACEHOLDER")
                                        attributes["autocomplete"] = "email"
                                        required = true
                                        attributes["autofocus"] = "true"
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") { +ctx.t("FORGOT_SEND_RESET") }
                            }

                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Reset password — set a new password via token link
    // -------------------------------------------------------------------------

    /**
     * @param token    The raw token from the query parameter — passed as a hidden field.
     * @param success  True after a successful password reset.
     */
    fun resetPasswordPage(
        tenantSlug: String,
        ctx: ViewContext,
        token: String,
        error: String? = null,
        success: Boolean = false,
        passwordPolicy: SecurityConfig = SecurityConfig(),
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_RESET", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("RESET_TITLE") }

                        if (success) {
                            div("alert alert-success") { +ctx.t("RESET_SUCCESS") }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("RESET_SIGN_IN_NEW") }
                            }
                        } else {
                            p("card-subtitle") { +ctx.t("RESET_SUBTITLE") }

                            if (error != null) {
                                div("alert alert-error") { +error }
                            }

                            form(
                                action = "/t/$tenantSlug/reset-password",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                input(type = InputType.hidden, name = "token") { value = token }

                                div("field") {
                                    label {
                                        htmlFor = "new_password"
                                        +ctx.t("NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "new_password") {
                                            id = "new_password"
                                            placeholder =
                                                ctx.t("PASSWORD_MIN_PLACEHOLDER", passwordPolicy.passwordMinLength)
                                            attributes["autocomplete"] = "new-password"
                                            required = true
                                            attributes["autofocus"] = "true"
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
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "new_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "confirm_password"
                                        +ctx.t("CONFIRM_NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "confirm_password") {
                                            id = "confirm_password"
                                            placeholder = ctx.t("CONFIRM_PASSWORD_PLACEHOLDER")
                                            attributes["autocomplete"] = "new-password"
                                            attributes["data-pw-mismatch-msg"] =
                                                ctx.t("PASSWORDS_DO_NOT_MATCH")
                                            required = true
                                        }
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "confirm_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") { +ctx.t("RESET_CHANGE_BUTTON") }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Accept invite — branded page where invited users set their password
    // -------------------------------------------------------------------------

    fun acceptInvitePage(
        tenantSlug: String,
        ctx: ViewContext,
        token: String,
        error: String? = null,
        success: Boolean = false,
        passwordPolicy: SecurityConfig = SecurityConfig(),
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_INVITE", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +"${ctx.t("INVITE_WELCOME_TITLE")} ${ctx.workspaceName}" }

                        if (success) {
                            div("alert alert-success") { +ctx.t("INVITE_ACCEPT_SUCCESS") }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("INVITE_ACCEPT_SIGN_IN") }
                            }
                        } else {
                            p("card-subtitle") { +ctx.t("INVITE_ACCEPT_SUBTITLE") }

                            if (error != null) {
                                div("alert alert-error") { +error }
                            }

                            form(
                                action = "/t/$tenantSlug/accept-invite",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                input(type = InputType.hidden, name = "token") { value = token }

                                div("field") {
                                    label {
                                        htmlFor = "new_password"
                                        +ctx.t("NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "new_password") {
                                            id = "new_password"
                                            placeholder =
                                                ctx.t("PASSWORD_MIN_PLACEHOLDER", passwordPolicy.passwordMinLength)
                                            attributes["autocomplete"] = "new-password"
                                            required = true
                                            attributes["autofocus"] = "true"
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
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "new_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "confirm_password"
                                        +ctx.t("CONFIRM_NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "confirm_password") {
                                            id = "confirm_password"
                                            placeholder = ctx.t("CONFIRM_PASSWORD_PLACEHOLDER")
                                            attributes["autocomplete"] = "new-password"
                                            attributes["data-pw-mismatch-msg"] =
                                                ctx.t("PASSWORDS_DO_NOT_MATCH")
                                            required = true
                                        }
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "confirm_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") {
                                    +ctx.t("INVITE_ACCEPT_SUBMIT")
                                }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Magic link — passwordless sign-in request + error pages
    // -------------------------------------------------------------------------

    /**
     * @param sent   True after the send endpoint runs — shows the "check your inbox" copy.
     *               Always `true` on redirect (user enumeration protection), regardless of
     *               whether an email was actually dispatched.
     */
    fun magicLinkPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
        sent: Boolean = false,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_MAGIC_LINK", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(ctx.workspaceName, ctx.theme) {
                    div("card") {
                        h1("card-title") { +ctx.t("MAGIC_LINK_TITLE") }

                        if (sent) {
                            p("card-subtitle") { +ctx.t("MAGIC_LINK_SUBTITLE_SENT") }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                            }
                        } else {
                            p("card-subtitle") { +ctx.t("MAGIC_LINK_SUBTITLE_FORM") }

                            if (error != null) {
                                div("alert alert-error") { +error }
                            }

                            form(
                                action = "/t/$tenantSlug/magic-link/send",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                div("field") {
                                    label {
                                        htmlFor = "email"
                                        +ctx.t("MAGIC_LINK_EMAIL_LABEL")
                                    }
                                    input(type = InputType.email, name = "email") {
                                        id = "email"
                                        placeholder = ctx.t("MAGIC_LINK_EMAIL_PLACEHOLDER")
                                        attributes["autocomplete"] = "email"
                                        required = true
                                        attributes["autofocus"] = "true"
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") { +ctx.t("MAGIC_LINK_SUBMIT") }
                            }

                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    fun emailOtpEnterEmailPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_EMAIL_OTP", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("EMAIL_OTP_TITLE") }
                        p("card-subtitle") { +ctx.t("EMAIL_OTP_SUBTITLE_FORM") }
                        if (error != null) {
                            div("alert alert-error") { +error }
                        }
                        form(
                            action = "/t/$tenantSlug/email-otp/send",
                            encType = FormEncType.applicationXWwwFormUrlEncoded,
                            method = FormMethod.post,
                        ) {
                            div("field") {
                                label {
                                    htmlFor = "email"
                                    +ctx.t("EMAIL_OTP_EMAIL_LABEL")
                                }
                                input(type = InputType.email, name = "email") {
                                    id = "email"
                                    placeholder = ctx.t("EMAIL_OTP_EMAIL_PLACEHOLDER")
                                    attributes["autocomplete"] = "email"
                                    required = true
                                    attributes["autofocus"] = "true"
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn") { +ctx.t("EMAIL_OTP_SEND_SUBMIT") }
                        }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    fun emailOtpEnterCodePage(
        tenantSlug: String,
        ctx: ViewContext,
        email: String,
        error: String? = null,
        resent: Boolean = false,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_EMAIL_OTP", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("EMAIL_OTP_VERIFY_TITLE") }
                        p("card-subtitle") {
                            +ctx.t("EMAIL_OTP_VERIFY_SUBTITLE")
                            +" "
                            span { style = "font-weight:500;"; +email }
                        }
                        if (resent) {
                            div("alert alert-success") { +ctx.t("EMAIL_OTP_VERIFY_RESENT") }
                        }
                        if (error != null) {
                            div("alert alert-error") { +error }
                        }
                        form(
                            action = "/t/$tenantSlug/email-otp/verify",
                            encType = FormEncType.applicationXWwwFormUrlEncoded,
                            method = FormMethod.post,
                        ) {
                            div("field") {
                                label {
                                    htmlFor = "code"
                                    +ctx.t("EMAIL_OTP_CODE_LABEL")
                                }
                                input(type = InputType.text, name = "code") {
                                    id = "code"
                                    placeholder = ctx.t("EMAIL_OTP_CODE_PLACEHOLDER")
                                    attributes["autocomplete"] = "one-time-code"
                                    attributes["inputmode"] = "numeric"
                                    attributes["pattern"] = "\\d{6}"
                                    attributes["maxlength"] = "6"
                                    required = true
                                    attributes["autofocus"] = "true"
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn") { +ctx.t("EMAIL_OTP_VERIFY_SUBMIT") }
                        }
                        form(
                            action = "/t/$tenantSlug/email-otp/send",
                            encType = FormEncType.applicationXWwwFormUrlEncoded,
                            method = FormMethod.post,
                        ) {
                            style = "margin-top:16px;text-align:center;"
                            input(type = InputType.hidden, name = "email") { value = email }
                            input(type = InputType.hidden, name = "resend") { value = "true" }
                            button(type = ButtonType.submit, classes = "btn-link") { +ctx.t("EMAIL_OTP_RESEND") }
                        }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/email-otp") { +ctx.t("EMAIL_OTP_USE_DIFFERENT_EMAIL") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    fun emailOtpErrorPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_EMAIL_OTP", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("EMAIL_OTP_ERROR_TITLE") }
                        div("alert alert-error") { +error }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/email-otp") { +ctx.t("EMAIL_OTP_USE_DIFFERENT_EMAIL") }
                        }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    /**
     * Shown when a magic-link consumption fails — expired, used, revoked, or
     * the user has a pending `CHANGE_PASSWORD` required action.
     */
    fun magicLinkErrorPage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_MAGIC_LINK", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("MAGIC_LINK_ERROR_TITLE") }
                        div("alert alert-error") { +error }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/magic-link") { +ctx.t("MAGIC_LINK_REQUEST_NEW") }
                        }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    /**
     * Shown when a provider signed the person in and the workspace refused to create an account.
     *
     * The sequence is what the copy has to survive: the person typed nothing wrong, authenticated
     * somewhere else, and came back rejected — so the page says the sign-in worked, says the
     * workspace is what refused, and names which of the two rules applied. It renders no password
     * field and offers no credential reset, because a page that looks like a wrong-password page
     * sends someone to change a credential that was never involved.
     */
    fun jitRefusedPage(
        tenantSlug: String,
        ctx: ViewContext,
        providerName: String,
        refusal: JitRefusal,
        reference: String,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_ACCESS_REFUSED", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(ctx.workspaceName, ctx.theme) {
                    div("card") {
                        h1("card-title") { +ctx.t("JIT_REFUSED_TITLE") }
                        p("card-subtitle") {
                            +ctx.t("JIT_REFUSED_AUTHENTICATED", providerName, ctx.workspaceName)
                        }
                        div("alert alert-error") {
                            p {
                                strong {
                                    +ctx.t(
                                        when (refusal) {
                                            JitRefusal.EMAIL_NOT_VERIFIED -> "JIT_REFUSED_EMAIL_NOT_VERIFIED_HEADING"
                                            JitRefusal.DOMAIN_NOT_ALLOWED -> "JIT_REFUSED_DOMAIN_NOT_ALLOWED_HEADING"
                                            JitRefusal.USERNAME_CONFLICT -> "JIT_REFUSED_USERNAME_CONFLICT_HEADING"
                                        },
                                    )
                                }
                            }
                            p {
                                +ctx.t(
                                    when (refusal) {
                                        JitRefusal.EMAIL_NOT_VERIFIED -> "JIT_REFUSED_EMAIL_NOT_VERIFIED_BODY"
                                        JitRefusal.DOMAIN_NOT_ALLOWED -> "JIT_REFUSED_DOMAIN_NOT_ALLOWED_BODY"
                                        JitRefusal.USERNAME_CONFLICT -> "JIT_REFUSED_USERNAME_CONFLICT_BODY"
                                    },
                                    providerName,
                                    ctx.workspaceName,
                                )
                            }
                        }
                        p("card-subtitle") { +ctx.t("JIT_REFUSED_REFERENCE", reference) }
                        div("footer-link") {
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Forced password change — admin-triggered temporary password flow
    // -------------------------------------------------------------------------

    /**
     * Consumes a TEMP_PASSWORD token. Shape mirrors [acceptInvitePage] —
     * different target URL, different copy.
     */
    fun forceChangePasswordPage(
        tenantSlug: String,
        ctx: ViewContext,
        token: String,
        error: String? = null,
        success: Boolean = false,
        passwordPolicy: SecurityConfig = SecurityConfig(),
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_FORCE_CHANGE", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("FORCE_CHANGE_TITLE") }

                        if (success) {
                            div("alert alert-success") { +ctx.t("FORCE_CHANGE_SUCCESS") }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("FORCE_CHANGE_GO_SIGN_IN") }
                            }
                        } else {
                            p("card-subtitle") {
                                +ctx.t("FORCE_CHANGE_SUBTITLE_LINE1")
                                +ctx.t("FORCE_CHANGE_SUBTITLE_LINE2")
                            }

                            if (error != null) {
                                div("alert alert-error") { +error }
                            }

                            form(
                                action = "/t/$tenantSlug/change-password",
                                encType = FormEncType.applicationXWwwFormUrlEncoded,
                                method = FormMethod.post,
                            ) {
                                input(type = InputType.hidden, name = "token") { value = token }

                                div("field") {
                                    label {
                                        htmlFor = "new_password"
                                        +ctx.t("NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "new_password") {
                                            id = "new_password"
                                            placeholder =
                                                ctx.t(
                                                    "PASSWORD_MIN_PLACEHOLDER",
                                                    passwordPolicy.passwordMinLength,
                                                )
                                            attributes["autocomplete"] = "new-password"
                                            required = true
                                            attributes["autofocus"] = "true"
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
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "new_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                div("field") {
                                    label {
                                        htmlFor = "confirm_password"
                                        +ctx.t("CONFIRM_NEW_PASSWORD")
                                    }
                                    div("field__input-wrap") {
                                        input(type = InputType.password, name = "confirm_password") {
                                            id = "confirm_password"
                                            placeholder = ctx.t("CONFIRM_PASSWORD_PLACEHOLDER")
                                            attributes["autocomplete"] = "new-password"
                                            attributes["data-pw-mismatch-msg"] = ctx.t("PASSWORDS_DO_NOT_MATCH")
                                            required = true
                                        }
                                        button(type = ButtonType.button, classes = "field__toggle-pw") {
                                            attributes["data-toggle-password"] = "confirm_password"
                                            attributes["aria-label"] = ctx.t("AUTH_SHOW_PASSWORD")
                                            attributes["aria-pressed"] = "false"
                                            attributes["data-visible"] = "false"
                                            inlineSvgIcon("eye", ctx.t("AUTH_ICON_SHOW"), cssClass = "icon-eye")
                                            inlineSvgIcon("eye-off", ctx.t("AUTH_ICON_HIDE"), cssClass = "icon-eye-off")
                                        }
                                    }
                                }
                                button(type = ButtonType.submit, classes = "btn") { +ctx.t("FORCE_CHANGE_SUBMIT") }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Email verification — result page after clicking the verification link
    // -------------------------------------------------------------------------

    /**
     * @param success  True when verification succeeded, false on any error.
     * @param message  Shown to the user — success confirmation or error reason.
     */
    fun verifyEmailPage(
        tenantSlug: String,
        ctx: ViewContext,
        success: Boolean,
        message: String,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_VERIFY_EMAIL", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        if (ctx.theme.logoUrl != null) {
                            img(src = ctx.theme.logoUrl, classes = "brand-logo", alt = ctx.workspaceName) {
                                width = "180"
                                height = "48"
                            }
                        } else {
                            div("brand-name") { +ctx.workspaceName }
                        }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("VERIFY_EMAIL_TITLE") }

                        if (success) {
                            div("alert alert-success") { +message }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("VERIFY_EMAIL_SIGN_IN") }
                            }
                        } else {
                            p("card-subtitle") { +ctx.t("VERIFY_EMAIL_PROBLEM") }
                            div("alert alert-error") { +message }
                            div("footer-link") {
                                a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                            }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Social registration completion page
    // -------------------------------------------------------------------------

    /**
     * Shown when a social login arrives but no existing account matches the provider identity
     * or email. The user confirms (or adjusts) their username before the account is created.
     *
     * @param prefillUsername  Derived from the provider email prefix — user can change it.
     * @param prefillFullName  From the provider profile name — user can change it.
     * @param email            From the provider — read-only (cannot be changed here).
     * @param providerName     Display name of the provider (e.g. "Google").
     */
    fun socialRegistrationPage(
        tenantSlug: String,
        ctx: ViewContext,
        providerName: String,
        email: String,
        prefillUsername: String = "",
        prefillFullName: String = "",
        error: String? = null,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_REGISTER", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                div("shell") {
                    div("brand") {
                        div("brand-name") { +ctx.workspaceName }
                    }
                    div("card") {
                        h1("card-title") { +ctx.t("SOCIAL_REG_TITLE") }
                        p("card-subtitle") { +ctx.t("SOCIAL_REG_SUBTITLE", providerName) }

                        if (error != null) {
                            div("alert alert-error") { +error }
                        }

                        form(
                            action = "complete-registration",
                            encType = FormEncType.applicationXWwwFormUrlEncoded,
                            method = FormMethod.post,
                        ) {
                            // Email is read-only — it comes from the provider and is shown for context
                            div("field") {
                                label {
                                    htmlFor = "email_display"
                                    +ctx.t("SOCIAL_REG_EMAIL_FROM", providerName)
                                }
                                input(type = InputType.email, name = "email_display") {
                                    id = "email_display"
                                    value = email
                                    disabled = true
                                }
                            }
                            div("field") {
                                label {
                                    htmlFor = "full_name"
                                    +ctx.t("REGISTER_FULL_NAME")
                                }
                                input(type = InputType.text, name = "full_name") {
                                    id = "full_name"
                                    placeholder = ctx.t("SOCIAL_REG_FULL_NAME_PLACEHOLDER")
                                    value = prefillFullName
                                    attributes["autocomplete"] = "name"
                                }
                            }
                            div("field") {
                                label {
                                    htmlFor = "username"
                                    +ctx.t("REGISTER_USERNAME")
                                }
                                input(type = InputType.text, name = "username") {
                                    id = "username"
                                    placeholder = ctx.t("SOCIAL_REG_USERNAME_PLACEHOLDER")
                                    value = prefillUsername
                                    attributes["autocomplete"] = "username"
                                    attributes["autofocus"] = "true"
                                    attributes["pattern"] = "[a-zA-Z0-9_]+"
                                    required = true
                                }
                            }
                            button(type = ButtonType.submit, classes = "btn") { +ctx.t("SOCIAL_REG_SUBMIT") }
                        }

                        div("footer-link") {
                            +ctx.t("REGISTER_HAS_ACCOUNT")
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("REGISTER_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // MFA Challenge page
    // -------------------------------------------------------------------------

    fun mfaChallengePage(
        tenantSlug: String,
        ctx: ViewContext,
        error: String? = null,
    ): HTML.() -> Unit =
        {
            head { authHead(ctx.t("AUTH_PAGE_TITLE_MFA", ctx.workspaceName), ctx.theme) }
            body {
                demoBanner()
                authShell(
                    ctx.workspaceName,
                    ctx.theme,
                    brandExtra = { div("brand-tagline") { +ctx.t("MFA_TAGLINE") } },
                ) {
                    div("card") {
                        h1("card-title") { +ctx.t("MFA_VERIFY_IDENTITY") }
                        p("card-subtitle") { +ctx.t("MFA_SUBTITLE") }

                        if (error != null) {
                            div("alert alert-error") { +error }
                        }

                        form(action = "/t/$tenantSlug/mfa-challenge", method = FormMethod.post) {
                            div("field") {
                                label {
                                    htmlFor = "code"
                                    +ctx.t("MFA_CODE_LABEL")
                                }
                                input(type = InputType.text, name = "code") {
                                    id = "code"
                                    placeholder = ctx.t("MFA_CODE_PLACEHOLDER")
                                    autoComplete = "off"
                                    autoFocus = true
                                    attributes["inputmode"] = "numeric"
                                    attributes["pattern"] = "[0-9a-fA-F]*"
                                }
                            }

                            button(type = ButtonType.submit, classes = "btn") { +ctx.t("MFA_VERIFY_BUTTON") }
                        }

                        div("footer-link") {
                            a(href = "/t/$tenantSlug/account/login") { +ctx.t("AUTH_BACK_TO_SIGN_IN") }
                        }
                    }
                    p("copyright") {
                        +ctx.t(
                            "AUTH_COPYRIGHT_TEMPLATE",
                            java.time.Year.now().toString(),
                            ctx.workspaceName,
                        )
                        a(href = "https://kotauth.com", target = "_blank") { +ctx.t("AUTH_KOTAUTH_LINK") }
                    }
                }
            }
        }

    /**
     * The contents of one social sign-in button. A provider key is an open string, so the
     * compiler cannot check this for exhaustiveness.
     *
     * This fallback is live as of Phase 2: [enabledProviders] is read from
     * identityProviderRepository, and the admin save route now writes any well-formed key, so a
     * brokered OIDC provider reaches this branch and is rendered from the operator's display
     * name, or from its own key when they set none.
     */
    private fun FlowContent.socialProviderButton(
        provider: LoginProvider,
        ctx: ViewContext,
    ) {
        val chosen = provider.displayName?.takeIf { it.isNotBlank() }
        val icon =
            when (provider.key) {
                ProviderKey.GOOGLE -> "google-logo"
                ProviderKey.GITHUB -> "github-logo"
                else -> "globe"
            }
        val fallbackName =
            when (provider.key) {
                ProviderKey.GOOGLE -> ctx.t("LOGIN_PROVIDER_GOOGLE")
                ProviderKey.GITHUB -> ctx.t("LOGIN_PROVIDER_GITHUB")
                else -> EnglishStrings.providerDisplayName(provider.key)
            }
        val label =
            when {
                chosen != null -> ctx.t("LOGIN_CONTINUE_GENERIC").replace("{provider}", chosen)
                provider.key == ProviderKey.GOOGLE -> ctx.t("LOGIN_CONTINUE_GOOGLE")
                provider.key == ProviderKey.GITHUB -> ctx.t("LOGIN_CONTINUE_GITHUB")
                else -> ctx.t("LOGIN_CONTINUE_GENERIC").replace("{provider}", fallbackName)
            }
        span("social-icon") { inlineSvgIcon(iconName = icon, ariaLabel = chosen ?: fallbackName) }
        +label
    }
}

/**
 * One provider as the sign-in page needs it: the key that builds the URL, and the label the
 * operator chose for it. Narrowing to [ProviderKey] at the call sites is what left
 * `IDP_DISPLAY_NAME_HINT` promising a label the button never showed.
 */
data class LoginProvider(
    val key: ProviderKey,
    val displayName: String? = null,
)

/** The enabled rows of a tenant, as the sign-in and registration pages want them. */
internal fun List<IdentityProvider>.asLoginProviders(): List<LoginProvider> =
    map { LoginProvider(it.provider, it.displayName) }

/**
 * Holds form values to re-populate the registration form after a failed submission.
 * Passwords are intentionally excluded — never re-populate password fields.
 */
data class RegisterPrefill(
    val username: String = "",
    val email: String = "",
    val fullName: String = "",
)
