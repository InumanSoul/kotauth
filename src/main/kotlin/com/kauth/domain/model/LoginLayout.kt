package com.kauth.domain.model

/**
 * Layout variant for the auth pages (login, register, magic-link, MFA, etc.).
 *
 * `CENTERED` (default) renders a single-column card centered on the page —
 * matches the pre-v1.21.0 look.
 *
 * `SPLIT` renders a two-column layout: a branded left panel (background image
 * or solid accent color, with a tagline) and the auth card on the right.
 * Collapses to `CENTERED` on narrow viewports (~640px).
 */
enum class LoginLayout {
    CENTERED,
    SPLIT,
}
