package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
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

    private fun HEAD.authHead(pageTitle: String, theme: TenantTheme) {
        title { +pageTitle }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

        // Favicon — uses tenant's custom favicon if set, otherwise default
        theme.faviconUrl?.let { url ->
            link(rel = "icon", href = url)
        }

        // 1. Inject theme variables first — base CSS reads from these
        style { unsafe { +theme.toCssVars() } }

        // 2. Base stylesheet that uses var(--token) exclusively
        link(rel = "stylesheet", href = "/static/kotauth-auth.css")
    }

    // -------------------------------------------------------------------------
    // Login page
    // -------------------------------------------------------------------------

    /**
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
        val nonce: String? = null
    ) {
        val isOAuthFlow: Boolean get() = !responseType.isNullOrBlank()

        /** Serializes non-null OAuth params to a query string (with leading '?'). */
        fun toQueryString(): String {
            val parts = mutableListOf<String>()
            responseType?.let        { parts += "response_type=$it" }
            clientId?.let            { parts += "oauth_client_id=$it" }
            redirectUri?.let         { parts += "redirect_uri=$it" }
            scope?.let               { parts += "scope=$it" }
            state?.let               { parts += "state=$it" }
            codeChallenge?.let       { parts += "code_challenge=$it" }
            codeChallengeMethod?.let { parts += "code_challenge_method=$it" }
            nonce?.let               { parts += "nonce=$it" }
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }
    }

    fun loginPage(
        tenantSlug: String,
        theme: TenantTheme = TenantTheme.DEFAULT,
        workspaceName: String = "KotAuth",
        error: String? = null,
        success: Boolean = false,
        oauthParams: OAuthParams = OAuthParams()
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Sign In", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Welcome back" }
                p("card-subtitle") { +"Sign in to your account" }

                if (success) {
                    div("alert alert-success") {
                        +"Account created successfully — please sign in."
                    }
                }
                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(
                    action = "/t/$tenantSlug/login",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post
                ) {
                    // OAuth2 state — passed through hidden fields so the POST handler
                    // can issue an authorization code and redirect after successful login
                    if (oauthParams.isOAuthFlow) {
                        oauthParams.responseType?.let { input(type = InputType.hidden, name = "response_type") { value = it } }
                        oauthParams.clientId?.let { input(type = InputType.hidden, name = "oauth_client_id") { value = it } }
                        oauthParams.redirectUri?.let { input(type = InputType.hidden, name = "redirect_uri") { value = it } }
                        oauthParams.scope?.let { input(type = InputType.hidden, name = "scope") { value = it } }
                        oauthParams.state?.let { input(type = InputType.hidden, name = "state") { value = it } }
                        oauthParams.codeChallenge?.let { input(type = InputType.hidden, name = "code_challenge") { value = it } }
                        oauthParams.codeChallengeMethod?.let { input(type = InputType.hidden, name = "code_challenge_method") { value = it } }
                        oauthParams.nonce?.let { input(type = InputType.hidden, name = "nonce") { value = it } }
                    }
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id = "username"
                            placeholder = "Enter your username"
                            attributes["autocomplete"] = "username"
                            required = true
                            attributes["autofocus"] = "true"
                        }
                    }
                    div("field") {
                        label { htmlFor = "password"; +"Password" }
                        input(type = InputType.password, name = "password") {
                            id = "password"
                            placeholder = "Enter your password"
                            attributes["autocomplete"] = "current-password"
                            required = true
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn") { +"Sign In" }
                }

                div("footer-link") {
                    a(href = "/t/$tenantSlug/forgot-password") { +"Forgot password?" }
                }
                div("footer-link") {
                    +"Don't have an account? "
                    a(href = "/t/$tenantSlug/register") { +"Create one" }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Registration page
    // -------------------------------------------------------------------------

    /**
     * @param tenantSlug  Used to build form action URLs.
     * @param theme       Visual identity — injected as CSS variables.
     * @param error       Inline error message from AuthService, or null.
     * @param prefill     Field values to preserve after a failed submission.
     */
    fun registerPage(
        tenantSlug: String,
        theme: TenantTheme = TenantTheme.DEFAULT,
        workspaceName: String = "KotAuth",
        error: String? = null,
        prefill: RegisterPrefill = RegisterPrefill()
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Create Account", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Create account" }
                p("card-subtitle") { +"Fill in your details to get started" }

                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(
                    action = "/t/$tenantSlug/register",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method = FormMethod.post
                ) {
                    div("field") {
                        label { htmlFor = "fullName"; +"Full Name" }
                        input(type = InputType.text, name = "fullName") {
                            id = "fullName"
                            placeholder = "Your full name"
                            attributes["autocomplete"] = "name"
                            value = prefill.fullName
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "email"; +"Email Address" }
                        input(type = InputType.email, name = "email") {
                            id = "email"
                            placeholder = "you@example.com"
                            attributes["autocomplete"] = "email"
                            value = prefill.email
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id = "username"
                            placeholder = "Choose a username"
                            attributes["autocomplete"] = "username"
                            value = prefill.username
                            required = true
                        }
                    }
                    div("divider") {}
                    div("field") {
                        label { htmlFor = "password"; +"Password" }
                        input(type = InputType.password, name = "password") {
                            id = "password"
                            placeholder = "Minimum 8 characters"
                            attributes["autocomplete"] = "new-password"
                            required = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "confirmPassword"; +"Confirm Password" }
                        input(type = InputType.password, name = "confirmPassword") {
                            id = "confirmPassword"
                            placeholder = "Repeat your password"
                            attributes["autocomplete"] = "new-password"
                            required = true
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn") { +"Create Account" }
                }

                div("footer-link") {
                    +"Already have an account? "
                    a(href = "/t/$tenantSlug/login") { +"Sign in" }
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
        tenantSlug    : String,
        theme         : TenantTheme = TenantTheme.DEFAULT,
        workspaceName : String = "KotAuth",
        error         : String? = null,
        sent          : Boolean = false
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Forgot Password", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Forgot password" }

                if (sent) {
                    p("card-subtitle") {
                        +"If an account exists for that email address, you'll receive a reset link shortly. Check your spam folder if you don't see it."
                    }
                    div("footer-link") {
                        a(href = "/t/$tenantSlug/login") { +"Back to sign in" }
                    }
                } else {
                    p("card-subtitle") { +"Enter your email address and we'll send you a link to reset your password." }

                    if (error != null) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/t/$tenantSlug/forgot-password",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post
                    ) {
                        div("field") {
                            label { htmlFor = "email"; +"Email address" }
                            input(type = InputType.email, name = "email") {
                                id = "email"
                                placeholder = "you@example.com"
                                attributes["autocomplete"] = "email"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +"Send reset link" }
                    }

                    div("footer-link") {
                        a(href = "/t/$tenantSlug/login") { +"Back to sign in" }
                    }
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
        tenantSlug    : String,
        theme         : TenantTheme = TenantTheme.DEFAULT,
        workspaceName : String = "KotAuth",
        token         : String,
        error         : String? = null,
        success       : Boolean = false
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Reset Password", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Reset password" }

                if (success) {
                    div("alert alert-success") {
                        +"Password changed successfully."
                    }
                    div("footer-link") {
                        a(href = "/t/$tenantSlug/login") { +"Sign in with your new password" }
                    }
                } else {
                    p("card-subtitle") { +"Enter your new password below." }

                    if (error != null) {
                        div("alert alert-error") { +error }
                    }

                    form(
                        action = "/t/$tenantSlug/reset-password",
                        encType = FormEncType.applicationXWwwFormUrlEncoded,
                        method = FormMethod.post
                    ) {
                        input(type = InputType.hidden, name = "token") { value = token }

                        div("field") {
                            label { htmlFor = "new_password"; +"New password" }
                            input(type = InputType.password, name = "new_password") {
                                id = "new_password"
                                placeholder = "Minimum 8 characters"
                                attributes["autocomplete"] = "new-password"
                                required = true
                                attributes["autofocus"] = "true"
                            }
                        }
                        div("field") {
                            label { htmlFor = "confirm_password"; +"Confirm new password" }
                            input(type = InputType.password, name = "confirm_password") {
                                id = "confirm_password"
                                placeholder = "Repeat your new password"
                                attributes["autocomplete"] = "new-password"
                                required = true
                            }
                        }
                        button(type = ButtonType.submit, classes = "btn") { +"Change password" }
                    }
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
        tenantSlug    : String,
        theme         : TenantTheme = TenantTheme.DEFAULT,
        workspaceName : String = "KotAuth",
        success       : Boolean,
        message       : String
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Email Verification", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Email verification" }

                if (success) {
                    div("alert alert-success") { +message }
                    div("footer-link") {
                        a(href = "/t/$tenantSlug/login") { +"Sign in to your account" }
                    }
                } else {
                    p("card-subtitle") { +"There was a problem with your verification link." }
                    div("alert alert-error") { +message }
                    div("footer-link") {
                        a(href = "/t/$tenantSlug/login") { +"Back to sign in" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // MFA Challenge page — Phase 3c
    // -------------------------------------------------------------------------

    fun mfaChallengePage(
        tenantSlug    : String,
        theme         : TenantTheme = TenantTheme.DEFAULT,
        workspaceName : String = "KotAuth",
        error         : String? = null,
        oauthParams   : OAuthParams = OAuthParams()
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Two-Factor Authentication", theme) }
        body {
            div("brand") {
                if (theme.logoUrl != null) {
                    img(src = theme.logoUrl, classes = "brand-logo", alt = workspaceName)
                } else {
                    div("brand-name") { +workspaceName }
                }
                div("brand-tagline") { +"Two-factor authentication" }
            }
            div("card") {
                h1("card-title") { +"Verify your identity" }
                p("card-subtitle") { +"Enter the 6-digit code from your authenticator app, or a recovery code." }

                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(action = "/t/$tenantSlug/mfa-challenge", method = FormMethod.post) {
                    // Preserve OAuth params through the MFA form submission
                    if (oauthParams.isOAuthFlow) {
                        oauthParams.responseType?.let { input(type = InputType.hidden, name = "response_type") { value = it } }
                        oauthParams.clientId?.let { input(type = InputType.hidden, name = "oauth_client_id") { value = it } }
                        oauthParams.redirectUri?.let { input(type = InputType.hidden, name = "redirect_uri") { value = it } }
                        oauthParams.scope?.let { input(type = InputType.hidden, name = "scope") { value = it } }
                        oauthParams.state?.let { input(type = InputType.hidden, name = "state") { value = it } }
                        oauthParams.codeChallenge?.let { input(type = InputType.hidden, name = "code_challenge") { value = it } }
                        oauthParams.codeChallengeMethod?.let { input(type = InputType.hidden, name = "code_challenge_method") { value = it } }
                        oauthParams.nonce?.let { input(type = InputType.hidden, name = "nonce") { value = it } }
                    }

                    div("form-group") {
                        label { htmlFor = "code"; +"Authentication code" }
                        input(type = InputType.text, name = "code") {
                            id = "code"
                            placeholder = "Enter 6-digit code or recovery code"
                            autoComplete = false
                            autoFocus = true
                            attributes["inputmode"] = "numeric"
                            attributes["pattern"] = "[0-9a-fA-F]*"
                        }
                    }

                    button(type = ButtonType.submit, classes = "btn-primary") { +"Verify" }
                }

                div("footer-link") {
                    a(href = "/t/$tenantSlug/login") { +"Back to sign in" }
                }
            }
        }
    }
}

/**
 * Holds form values to re-populate the registration form after a failed submission.
 * Passwords are intentionally excluded — never re-populate password fields.
 */
data class RegisterPrefill(
    val username: String = "",
    val email: String = "",
    val fullName: String = ""
)
