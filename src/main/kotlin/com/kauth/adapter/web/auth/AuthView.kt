package com.kauth.adapter.web.auth

import com.kauth.domain.model.SocialProvider
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

        // Favicon — tenant custom URL takes precedence; fallback to bundled static assets
        if (theme.faviconUrl != null) {
            link(rel = "icon", href = theme.faviconUrl)
        } else {
            link(rel = "icon", type = "image/x-icon", href = "/static/favicon/favicon.ico")
            link(rel = "icon", type = "image/png",    href = "/static/favicon/favicon-32x32.png") { attributes["sizes"] = "32x32" }
            link(rel = "icon", type = "image/png",    href = "/static/favicon/favicon-16x16.png") { attributes["sizes"] = "16x16" }
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
        oauthParams: OAuthParams = OAuthParams(),
        enabledProviders: List<SocialProvider> = emptyList()
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

                // Social login buttons — only shown when providers are configured
                if (enabledProviders.isNotEmpty()) {
                    div("social-divider") {
                        span { +"or continue with" }
                    }
                    div("social-buttons") {
                        for (prov in enabledProviders) {
                            val qs = oauthParams.toQueryString()
                            a(
                                href    = "/t/$tenantSlug/auth/social/${prov.value}/redirect$qs",
                                classes = "btn btn-social btn-social-${prov.value}"
                            ) {
                                when (prov) {
                                    SocialProvider.GOOGLE -> {
                                        // Inline SVG Google logo
                                        span("social-icon") {
                                            unsafe {
                                                +"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18">
                                                    <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                                                    <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                                                    <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z"/>
                                                    <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
                                                </svg>"""
                                            }
                                        }
                                        +"Continue with Google"
                                    }
                                    SocialProvider.GITHUB -> {
                                        span("social-icon") {
                                            unsafe {
                                                +"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18">
                                                    <path fill="currentColor" d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/>
                                                </svg>"""
                                            }
                                        }
                                        +"Continue with GitHub"
                                    }
                                }
                            }
                        }
                    }
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
        tenantSlug       : String,
        theme            : TenantTheme = TenantTheme.DEFAULT,
        workspaceName    : String = "KotAuth",
        error            : String? = null,
        prefill          : RegisterPrefill = RegisterPrefill(),
        enabledProviders : List<SocialProvider> = emptyList()
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

                // Social login buttons — same providers as the login page.
                // Clicking one initiates the OAuth flow; if the account doesn't exist
                // yet the callback redirects to complete-registration automatically.
                if (enabledProviders.isNotEmpty()) {
                    div("social-divider") {
                        span { +"or sign up with" }
                    }
                    div("social-buttons") {
                        for (prov in enabledProviders) {
                            a(
                                href    = "/t/$tenantSlug/auth/social/${prov.value}/redirect",
                                classes = "btn btn-social btn-social-${prov.value}"
                            ) {
                                span("social-icon") {}
                                +prov.displayName
                            }
                        }
                    }
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
        tenantSlug      : String,
        theme           : TenantTheme = TenantTheme.DEFAULT,
        workspaceName   : String = "KotAuth",
        providerName    : String,
        email           : String,
        prefillUsername : String = "",
        prefillFullName : String = "",
        error           : String? = null
    ): HTML.() -> Unit = {
        head { authHead("$workspaceName | Create Account", theme) }
        body {
            div("brand") {
                div("brand-name") { +workspaceName }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"One last step" }
                p("card-subtitle") {
                    +"You're signing in with $providerName. Choose a username to complete your account."
                }

                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(
                    action  = "complete-registration",
                    encType = FormEncType.applicationXWwwFormUrlEncoded,
                    method  = FormMethod.post
                ) {
                    // Email is read-only — it comes from the provider and is shown for context
                    div("field") {
                        label { htmlFor = "email_display"; +"Email (from $providerName)" }
                        input(type = InputType.email, name = "email_display") {
                            id       = "email_display"
                            value    = email
                            disabled = true
                        }
                    }
                    div("field") {
                        label { htmlFor = "full_name"; +"Full Name" }
                        input(type = InputType.text, name = "full_name") {
                            id          = "full_name"
                            placeholder = "Your display name"
                            value       = prefillFullName
                            attributes["autocomplete"] = "name"
                        }
                    }
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id          = "username"
                            placeholder = "letters, numbers, underscores"
                            value       = prefillUsername
                            attributes["autocomplete"] = "username"
                            attributes["autofocus"]    = "true"
                            attributes["pattern"]      = "[a-zA-Z0-9_]+"
                            required = true
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn") { +"Create account" }
                }

                div("footer-link") {
                    +"Already have an account? "
                    a(href = "/t/$tenantSlug/login") { +"Sign in" }
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
