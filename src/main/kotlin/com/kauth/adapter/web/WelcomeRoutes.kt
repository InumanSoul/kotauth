package com.kauth.adapter.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*

/**
 * Welcome / status page served at GET /.
 *
 * Development mode  → runs live health checks (DB + config) and renders them on the page.
 * Production mode   → health details are omitted; a notice links to /health/ready instead.
 *
 * @param baseUrl       Public base URL (KAUTH_BASE_URL), forwarded to the config check.
 * @param appInfo       Build-time metadata loaded once at startup via [loadAppInfo].
 * @param startTime     [System.currentTimeMillis] captured at process start for uptime tracking.
 * @param isDevelopment True when KAUTH_ENV != "production".
 */
fun Route.welcomeRoutes(
    baseUrl: String,
    appInfo: AppInfo,
    startTime: Long,
    isDevelopment: Boolean,
    encryptionAvailable: Boolean,
) {
    get("/") {
        val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000

        val health =
            if (isDevelopment) {
                val db = pingDatabase()
                val config = checkConfig(baseUrl, encryptionAvailable)
                WelcomeView.HealthInfo(
                    dbStatus = db.status,
                    dbLatencyMs = db.latencyMs,
                    dbDetail = db.detail,
                    configStatus = config.status,
                    configWarnings = config.warnings,
                )
            } else {
                null
            }

        call.respondHtml(HttpStatusCode.OK, WelcomeView.welcomePage(appInfo, uptimeSeconds, health))
    }
}
