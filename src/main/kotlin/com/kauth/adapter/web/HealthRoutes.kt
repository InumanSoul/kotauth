package com.kauth.adapter.web

import com.kauth.adapter.persistence.TenantsTable
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

// ---------------------------------------------------------------------------
// Response shapes
// ---------------------------------------------------------------------------

@Serializable
data class LivenessResponse(
    val status: String,
    val timestamp: String
)

@Serializable
data class DbCheckResult(
    val status: String,
    val latencyMs: Long? = null,
    val detail: String? = null
)

@Serializable
data class ConfigCheckResult(
    val status: String,
    val baseUrl: String,
    val httpsEnforced: Boolean,
    val secretKeyPresent: Boolean,
    val warnings: List<String> = emptyList()
)

@Serializable
data class ReadinessChecks(
    val database: DbCheckResult,
    val config: ConfigCheckResult
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val timestamp: String,
    val checks: ReadinessChecks
)

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

/**
 * Health routes for liveness and readiness probing.
 *
 *  GET /health        — liveness: is the process alive and accepting connections?
 *                       Used by Docker/k8s to decide whether to restart the container.
 *                       Always returns 200 if the JVM is running.
 *
 *  GET /health/ready  — readiness: is it safe to route traffic to this instance?
 *                       Checks DB connectivity and config validity.
 *                       Returns 200 when ready, 503 when not_ready.
 *                       Used by Docker depends_on / k8s readiness probe.
 */
fun Route.healthRoutes(baseUrl: String) {

    // -- Liveness ------------------------------------------------------------
    get("/health") {
        call.respond(LivenessResponse(
            status    = "up",
            timestamp = Instant.now().toString()
        ))
    }

    // -- Readiness -----------------------------------------------------------
    get("/health/ready") {
        val dbCheck     = pingDatabase()
        val configCheck = checkConfig(baseUrl)

        // The container is only "ready" when the DB is reachable.
        // Config issues are surfaced as warnings, not hard failures, so that
        // Docker can still start and the operator can see the problem clearly.
        val overallStatus = if (dbCheck.status == "ok") "ready" else "not_ready"
        val httpStatus    = if (overallStatus == "ready") HttpStatusCode.OK
                            else HttpStatusCode.ServiceUnavailable

        call.respond(httpStatus, ReadinessResponse(
            status    = overallStatus,
            timestamp = Instant.now().toString(),
            checks    = ReadinessChecks(
                database = dbCheck,
                config   = configCheck
            )
        ))
    }
}

// ---------------------------------------------------------------------------
// Check implementations
// ---------------------------------------------------------------------------

/**
 * Queries the tenants table (guaranteed to exist post-migration) inside an
 * Exposed transaction to verify the DB connection pool is alive and reachable.
 * Uses the same Exposed DSL pattern as the rest of the persistence layer.
 * Measures round-trip latency to surface degraded performance early.
 */
private fun pingDatabase(): DbCheckResult {
    val start = System.currentTimeMillis()
    return try {
        transaction {
            TenantsTable.selectAll().firstOrNull()
        }
        DbCheckResult(
            status    = "ok",
            latencyMs = System.currentTimeMillis() - start
        )
    } catch (e: Exception) {
        DbCheckResult(
            status = "error",
            detail = e.message ?: "unknown database error"
        )
    }
}

/**
 * Validates runtime configuration and reports any problems that would
 * affect correctness or security — without terminating the process.
 * Startup enforcement (exitProcess) handles hard failures; this surfaces
 * soft warnings for operators checking the health endpoint after deploy.
 */
private fun checkConfig(baseUrl: String): ConfigCheckResult {
    val isHttps     = baseUrl.startsWith("https://")
    val isLocalhost = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
    val secretKeyPresent = EncryptionService.isAvailable

    val warnings = buildList {
        if (!isHttps && !isLocalhost) {
            add("KAUTH_BASE_URL is not HTTPS on a non-localhost host — OAuth2 providers will reject redirect URIs")
        }
        if (!secretKeyPresent) {
            add("KAUTH_SECRET_KEY is not set — portal sessions are ephemeral and SMTP passwords cannot be stored")
        }
    }

    return ConfigCheckResult(
        status           = if (warnings.isEmpty()) "ok" else "warn",
        baseUrl          = baseUrl,
        httpsEnforced    = isHttps,
        secretKeyPresent = secretKeyPresent,
        warnings         = warnings
    )
}
