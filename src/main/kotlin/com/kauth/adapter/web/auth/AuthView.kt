package com.kauth.adapter.web.auth

import kotlinx.html.*

/**
 * View layer for the auth module.
 *
 * These are pure functions: data in → HTML out.
 * No HTTP context, no service calls, no side effects.
 * Each function returns a lambda that builds an HTML document.
 *
 * This is the "passive view" from MVP, adapted for server-side rendering.
 * Routes orchestrate; views render. Swap kotlinx.html for Thymeleaf here
 * without touching a single route or service.
 */
object AuthView {

    // -------------------------------------------------------------------------
    // Shared design tokens — single source of truth for the UI
    // -------------------------------------------------------------------------
    private object Colors {
        const val BG_DEEP = "#0f0f13"
        const val BG_CARD = "#1a1a24"
        const val BG_INPUT = "#252532"
        const val BORDER = "#2e2e3e"
        const val ACCENT = "#bb86fc"
        const val ACCENT_HOVER = "#9965f4"
        const val TEXT_PRIMARY = "#e8e8f0"
        const val TEXT_MUTED = "#6b6b80"
        const val ERROR_BG = "#2a1a1a"
        const val ERROR_BORDER = "#cf6679"
        const val ERROR_TEXT = "#ff8a9b"
        const val SUCCESS_BG = "#1a2a1a"
        const val SUCCESS_BORDER = "#4caf50"
        const val SUCCESS_TEXT = "#81c784"
    }

    private fun baseStyles(): String = """
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background: ${Colors.BG_DEEP};
            color: ${Colors.TEXT_PRIMARY};
            font-family: 'Inter', system-ui, -apple-system, sans-serif;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 1.5rem;
        }
        .brand { margin-bottom: 2rem; text-align: center; }
        .brand-name {
            font-size: 1.25rem;
            font-weight: 700;
            color: ${Colors.ACCENT};
            letter-spacing: 0.05em;
            text-transform: uppercase;
        }
        .brand-tagline { font-size: 0.75rem; color: ${Colors.TEXT_MUTED}; margin-top: 0.25rem; }
        .card {
            background: ${Colors.BG_CARD};
            border: 1px solid ${Colors.BORDER};
            border-radius: 12px;
            padding: 2rem;
            width: 100%;
            max-width: 420px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
        }
        .card-title {
            font-size: 1.4rem;
            font-weight: 600;
            margin-bottom: 0.25rem;
            color: ${Colors.TEXT_PRIMARY};
        }
        .card-subtitle {
            font-size: 0.85rem;
            color: ${Colors.TEXT_MUTED};
            margin-bottom: 1.75rem;
        }
        .field { margin-bottom: 1rem; }
        label {
            display: block;
            font-size: 0.8rem;
            font-weight: 500;
            color: ${Colors.TEXT_MUTED};
            margin-bottom: 0.4rem;
            text-transform: uppercase;
            letter-spacing: 0.04em;
        }
        input {
            width: 100%;
            padding: 0.75rem 1rem;
            background: ${Colors.BG_INPUT};
            border: 1px solid ${Colors.BORDER};
            border-radius: 6px;
            color: ${Colors.TEXT_PRIMARY};
            font-size: 0.95rem;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus { border-color: ${Colors.ACCENT}; }
        input::placeholder { color: ${Colors.TEXT_MUTED}; }
        .btn {
            width: 100%;
            padding: 0.85rem;
            background: ${Colors.ACCENT};
            border: none;
            border-radius: 6px;
            color: #0f0f13;
            font-size: 0.95rem;
            font-weight: 700;
            cursor: pointer;
            transition: background 0.2s, transform 0.1s;
            margin-top: 0.5rem;
            letter-spacing: 0.02em;
        }
        .btn:hover { background: ${Colors.ACCENT_HOVER}; }
        .btn:active { transform: scale(0.98); }
        .alert {
            padding: 0.75rem 1rem;
            border-radius: 6px;
            font-size: 0.85rem;
            margin-bottom: 1.25rem;
            border-width: 1px;
            border-style: solid;
        }
        .alert-error {
            background: ${Colors.ERROR_BG};
            border-color: ${Colors.ERROR_BORDER};
            color: ${Colors.ERROR_TEXT};
        }
        .alert-success {
            background: ${Colors.SUCCESS_BG};
            border-color: ${Colors.SUCCESS_BORDER};
            color: ${Colors.SUCCESS_TEXT};
        }
        .footer-link {
            text-align: center;
            margin-top: 1.25rem;
            font-size: 0.85rem;
            color: ${Colors.TEXT_MUTED};
        }
        .footer-link a { color: ${Colors.ACCENT}; text-decoration: none; }
        .footer-link a:hover { text-decoration: underline; }
        .divider {
            height: 1px;
            background: ${Colors.BORDER};
            margin: 1.5rem 0;
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // Login page
    // -------------------------------------------------------------------------

    /**
     * @param tenantSlug The tenant slug — used to build form action URLs.
     * @param error      Inline error message to display, or null for a clean form.
     * @param success    True when arriving from a successful registration — shows a success banner.
     */
    fun loginPage(tenantSlug: String, error: String? = null, success: Boolean = false): HTML.() -> Unit = {
        head {
            title { +"KotAuth | Sign In" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +baseStyles() } }
        }
        body {
            div("brand") {
                div("brand-name") { +"KotAuth" }
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

                form(action = "/t/$tenantSlug/login", encType = FormEncType.applicationXWwwFormUrlEncoded, method = FormMethod.post) {
                    div("field") {
                        label { htmlFor = "username"; +"Username" }
                        input(type = InputType.text, name = "username") {
                            id = "username"
                            placeholder = "Enter your username"
                            attributes["autocomplete"] = "username"
                            required = true
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
     * @param tenantSlug The tenant slug — used to build form action URLs.
     * @param error      Inline error message from AuthService, or null for a clean form.
     * @param prefill    Field values to preserve after a failed submission.
     */
    fun registerPage(
        tenantSlug: String,
        error: String? = null,
        prefill: RegisterPrefill = RegisterPrefill()
    ): HTML.() -> Unit = {
        head {
            title { +"KotAuth | Create Account" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            style { unsafe { +baseStyles() } }
        }
        body {
            div("brand") {
                div("brand-name") { +"KotAuth" }
                div("brand-tagline") { +"Modernized Identity & Access Management" }
            }
            div("card") {
                h1("card-title") { +"Create account" }
                p("card-subtitle") { +"Fill in your details to get started" }

                if (error != null) {
                    div("alert alert-error") { +error }
                }

                form(action = "/t/$tenantSlug/register", encType = FormEncType.applicationXWwwFormUrlEncoded, method = FormMethod.post) {
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
