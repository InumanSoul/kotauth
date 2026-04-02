package com.kauth.infrastructure

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(VersionCheckService::class.java)

private const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L // 6 hours
private const val HTTP_TIMEOUT_SECONDS = 15L

data class VersionCheckResult(
    val currentVersion: String,
    val latestVersion: String?,
    val urgency: String?,
    val updateAvailable: Boolean,
    val releaseUrl: String?,
    val checkedAt: Instant?,
    val enabled: Boolean,
)

class VersionCheckService(
    private val currentVersion: String,
    private val manifestUrl: String,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
    private val fetcher: ((String) -> String)? = null,
) {
    @Volatile
    private var cached: VersionCheckResult =
        VersionCheckResult(
            currentVersion = currentVersion,
            latestVersion = null,
            urgency = null,
            updateAvailable = false,
            releaseUrl = null,
            checkedAt = null,
            enabled = enabled,
        )

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    fun start() {
        if (!enabled) {
            log.info("Version check disabled (KAUTH_UPDATE_CHECK=false)")
            return
        }
        scope.launch {
            while (isActive) {
                try {
                    cached = fetchLatest()
                    if (cached.updateAvailable) {
                        log.info(
                            "KotAuth update available: {} -> {} ({})",
                            currentVersion,
                            cached.latestVersion,
                            cached.releaseUrl,
                        )
                    }
                } catch (e: Exception) {
                    log.debug("Version check failed (will retry in 6h): {}", e.message)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun current(): VersionCheckResult = cached

    private fun fetchLatest(): VersionCheckResult {
        val body =
            if (fetcher != null) {
                fetcher.invoke(manifestUrl)
            } else {
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(manifestUrl))
                        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                        .header("User-Agent", "KotAuth/$currentVersion (version-check)")
                        .GET()
                        .build()
                httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
            }

        val json = Json.parseToJsonElement(body).jsonObject
        val latest =
            json["version"]?.jsonPrimitive?.content
                ?: error("Manifest missing 'version' field")
        val releaseUrl = json["releaseUrl"]?.jsonPrimitive?.content
        val urgency = json["urgency"]?.jsonPrimitive?.content ?: "info"

        return VersionCheckResult(
            currentVersion = currentVersion,
            latestVersion = latest,
            urgency = urgency,
            updateAvailable = isNewer(latest, currentVersion),
            releaseUrl = releaseUrl,
            checkedAt = Instant.now(),
            enabled = true,
        )
    }
}

internal fun isNewer(
    latest: String,
    current: String,
): Boolean {
    fun parse(v: String): List<Int> {
        val parts =
            v
                .trimStart('v')
                .split(".")
                .map { it.substringBefore("-").toIntOrNull() ?: 0 }
        return if (parts.size >= 3) parts.take(3) else parts + List(3 - parts.size) { 0 }
    }

    val l = parse(latest)
    val c = parse(current)
    for (i in 0..2) {
        if (l[i] > c[i]) return true
        if (l[i] < c[i]) return false
    }
    return false
}
