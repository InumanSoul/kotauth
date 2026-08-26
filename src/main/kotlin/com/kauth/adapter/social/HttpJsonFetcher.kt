package com.kauth.adapter.social

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** An outbound GET reduced to what discovery and JWKS need. The body may carry a key — never log it. */
data class HttpJsonResponse(
    val statusCode: Int,
    val body: String,
)

/** The far end sent more than the caller agreed to read. Distinct so adapters can say so. */
class ResponseTooLargeException(
    val maxBytes: Long,
    url: String,
) : Exception("Response from $url exceeded the $maxBytes byte limit.")

/**
 * The single outbound-GET seam the OIDC adapters fetch through, so tests can drive them
 * without a network. A transport failure is thrown; both adapters turn it into a failed Result.
 */
fun interface HttpJsonFetcher {
    fun get(url: String): HttpJsonResponse
}

/**
 * Default fetcher over java.net.http.HttpClient, matching the social adapters — no new dependency.
 *
 * The body is read through a hard [maxBodyBytes] ceiling. A discovery or JWKS document is a few
 * kilobytes; without the ceiling a mistyped issuer URL pointing at something large is an ordinary
 * operator error that ends as an OOM in the server rather than a configuration failure.
 */
class JdkHttpJsonFetcher(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) : HttpJsonFetcher {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            // Redirects are followed; the discovery adapter's issuer check is what makes that safe.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun get(url: String): HttpJsonResponse {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .timeout(timeout)
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body().use { readBounded(it, maxBodyBytes, url) }
        return HttpJsonResponse(response.statusCode(), body)
    }

    companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 256L * 1024L
    }
}

/**
 * Reads at most [maxBytes] from [stream], throwing [ResponseTooLargeException] the moment one more
 * byte arrives. Nothing beyond the ceiling is ever held in memory.
 */
internal fun readBounded(
    stream: InputStream,
    maxBytes: Long,
    url: String,
): String {
    val buffer = ByteArray(DEFAULT_CHUNK_BYTES)
    val collected = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ResponseTooLargeException(maxBytes, url)
        collected.write(buffer, 0, read)
    }
    return collected.toString(StandardCharsets.UTF_8)
}

private const val DEFAULT_CHUNK_BYTES = 8 * 1024
