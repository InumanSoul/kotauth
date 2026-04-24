package com.kauth.infrastructure

import com.kauth.domain.port.BreachedPasswordPort
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * [BreachedPasswordPort] implementation using the Have I Been Pwned
 * [k-Anonymity API](https://haveibeenpwned.com/API/v3#PwnedPasswords).
 *
 * Only the first 5 hex characters of the SHA-1 hash leave the process;
 * the server returns ~500 suffix/count pairs, and we match the full hash
 * locally. The `Add-Padding: true` request header instructs HIBP to pad the
 * response to a uniform size so response length cannot leak prefix frequency.
 *
 * Fail-open: any network error, non-200 status, parse failure, or timeout
 * results in `false` (password allowed). The rationale is that an outage on
 * HIBP's side should not block user registrations or password changes —
 * breach detection is a policy enhancement, not an access gate.
 *
 * Responses are cached per-prefix for [ttlMillis] (default 60s). Caching by
 * full hash would create a timing oracle; by prefix, a cache hit merely
 * indicates that someone — anyone — checked a password with a matching prefix
 * recently (~500 possible hashes per prefix).
 */
class HibpBreachedPasswordAdapter(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Instant = Instant::now,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val baseUrl: String = HIBP_BASE_URL,
    private val userAgent: String = "kotauth/1.6",
) : BreachedPasswordPort {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CachedRange(
        val suffixCounts: Map<String, Long>,
        val expireAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CachedRange>()

    override fun isBreached(rawPassword: String): Boolean {
        if (rawPassword.isEmpty()) return false
        return try {
            val sha1 = sha1Hex(rawPassword).uppercase()
            val prefix = sha1.take(5)
            val suffix = sha1.drop(5)
            val range = fetchRange(prefix)
            range[suffix] != null
        } catch (e: Exception) {
            log.warn("HIBP breach check failed, allowing password through: {}", e.message)
            false
        }
    }

    private fun fetchRange(prefix: String): Map<String, Long> {
        val now = clock()
        val cached = cache[prefix]
        if (cached != null && cached.expireAt.isAfter(now)) {
            return cached.suffixCounts
        }

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/range/$prefix"))
                .header("Add-Padding", "true")
                .header("User-Agent", userAgent)
                .timeout(timeout)
                .GET()
                .build()

        val response = httpClient.send(request, BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("HIBP responded with status {} for prefix {}", response.statusCode(), prefix)
            return emptyMap()
        }

        val parsed = parseResponse(response.body())
        cache[prefix] = CachedRange(parsed, now.plusMillis(ttlMillis))
        return parsed
    }

    private fun parseResponse(body: String): Map<String, Long> =
        body
            .lineSequence()
            .filter { it.contains(':') }
            .mapNotNull { line ->
                val parts = line.split(':', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val hashSuffix = parts[0].trim()
                val count = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                // Padding entries have count = 0 — skip.
                if (count == 0L) null else hashSuffix to count
            }.toMap()

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val HIBP_BASE_URL = "https://api.pwnedpasswords.com"
        const val DEFAULT_TTL_MILLIS: Long = 60_000L
    }
}
