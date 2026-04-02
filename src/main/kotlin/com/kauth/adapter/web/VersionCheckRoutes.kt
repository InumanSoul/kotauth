package com.kauth.adapter.web

import com.kauth.infrastructure.VersionCheckService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class VersionResponse(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean,
    val urgency: String? = null,
    val releaseUrl: String? = null,
    val checkedAt: String? = null,
    val enabled: Boolean,
)

fun Route.versionCheckRoutes(service: VersionCheckService) {
    get("/health/version") {
        val result = service.current()
        call.respond(
            VersionResponse(
                currentVersion = result.currentVersion,
                latestVersion = result.latestVersion,
                updateAvailable = result.updateAvailable,
                urgency = result.urgency,
                releaseUrl = result.releaseUrl,
                checkedAt = result.checkedAt?.toString(),
                enabled = result.enabled,
            ),
        )
    }
}
