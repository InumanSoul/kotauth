package com.kauth.adapter.social

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** An outbound GET reduced to what discovery and JWKS need. The body may carry a key — never log it. */
data class HttpJsonResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * The single outbound-GET seam the OIDC adapters fetch through, so tests can drive them
 * without a network. A transport failure is thrown; both adapters turn it into a failed Result.
 */
fun interface HttpJsonFetcher {
    fun get(url: String): HttpJsonResponse
}

/** Default fetcher over java.net.http.HttpClient, matching the social adapters — no new dependency. */
class JdkHttpJsonFetcher(
    private val timeout: Duration = Duration.ofSeconds(10),
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
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpJsonResponse(response.statusCode(), response.body())
    }
}
