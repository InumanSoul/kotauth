package com.kauth.adapter.web

import kotlinx.html.*

/**
 * Global demo mode flag — set once at startup, read by all view layers.
 *
 * Kept outside any specific view object so both admin shell, auth pages,
 * and the welcome page can reference it without cross-package coupling.
 */
object DemoConfig {
    @Volatile
    var enabled: Boolean = false
}

/**
 * Thin (2px) amber indicator strip shown on every page when KAUTH_DEMO_MODE=true.
 *
 * Credentials are documented in the separate demo docs repo — not rendered
 * inline so nothing in the UI drifts when the fixture usernames change.
 *
 * Styled by frontend/css/components/demo-banner.css (imported in admin and
 * auth bundles). ARIA label carries the "demo mode" semantics for AT users
 * since there's no visible text.
 */
fun BODY.demoBanner() {
    if (!DemoConfig.enabled) return
    div("demo-banner") {
        attributes["role"] = "status"
        attributes["aria-label"] = "Demo mode"
    }
}
