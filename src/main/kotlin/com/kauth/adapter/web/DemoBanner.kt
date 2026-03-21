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
 * Fixed-position banner shown on every page when KAUTH_DEMO_MODE=true.
 *
 * Displays demo credentials and a reset notice. Styled by
 * frontend/css/components/demo-banner.css (imported in both admin and auth
 * bundles). The DEMO label reuses the existing .badge .badge--warn component.
 *
 * Layout offset is handled via CSS:
 *   Admin — .demo-banner ~ .shell { height: calc(100vh - 36px) }
 *   Auth  — body:has(.demo-banner) { padding-top: 36px }
 */
fun BODY.demoBanner() {
    if (!DemoConfig.enabled) return
    div("demo-banner") {
        span("badge badge--warn") { +"Demo" }
        span {
            +"Data resets periodically · Admin: "
            code { +"admin" }
            +" / "
            code { +"changeme123!" }
            +" · Acme: "
            code { +"sarah.chen" }
            +" / "
            code { +"Demo1234!" }
        }
    }
}
