package com.kauth.adapter.web

import com.kauth.infrastructure.VersionCheckService
import kotlinx.html.*

/**
 * Global update check state — set once at startup, read by all admin views.
 *
 * Mirrors the [DemoConfig] pattern: a singleton written once at startup,
 * read on every admin shell render. The [VersionCheckService] updates its
 * internal cache in the background; this object reads from it.
 */
object UpdateBannerConfig {
    @Volatile
    private var service: VersionCheckService? = null

    fun register(s: VersionCheckService) {
        service = s
    }

    fun isUpdateAvailable(): Boolean = service?.current()?.updateAvailable == true

    fun latestVersion(): String? = service?.current()?.latestVersion

    fun releaseUrl(): String? = service?.current()?.releaseUrl

    fun isSecurity(): Boolean = service?.current()?.urgency == "security"
}

/**
 * Update notification chip for the admin topbar.
 *
 * Renders a compact chip in topbar-right when a new KotAuth version
 * is available. Uses accent color for routine updates, red for security.
 * Dismissible via localStorage (handled by update-check.js).
 *
 * Renders nothing when:
 *   - No update is available
 *   - Version check is disabled
 *   - The service hasn't completed its first check yet
 */
fun DIV.updateChip() {
    if (!UpdateBannerConfig.isUpdateAvailable()) return
    val version = UpdateBannerConfig.latestVersion() ?: return
    val url = UpdateBannerConfig.releaseUrl()
    val isSecurity = UpdateBannerConfig.isSecurity()
    val modifier = if (isSecurity) " update-chip--security" else ""

    div("update-chip$modifier") {
        attributes["role"] = if (isSecurity) "alert" else "status"
        attributes["aria-label"] = "KotAuth update available: v$version"
        attributes["data-dismiss-version"] = version
        span("update-chip__dot") { attributes["aria-hidden"] = "true" }
        span("update-chip__label") {
            if (isSecurity) +"Security update — v$version" else +"v$version available"
        }
        if (url != null) {
            a(
                href = url,
                classes = "update-chip__link",
            ) {
                target = "_blank"
                attributes["rel"] = "noopener noreferrer"
                if (isSecurity) +"Advisory" else +"Release notes"
            }
        }
        button(type = ButtonType.button, classes = "update-chip__dismiss") {
            attributes["aria-label"] = "Dismiss update notice"
            +"\u00d7"
        }
    }
}
