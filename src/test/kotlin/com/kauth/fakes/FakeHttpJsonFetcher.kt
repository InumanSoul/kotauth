package com.kauth.fakes

import com.kauth.adapter.social.HttpJsonFetcher
import com.kauth.adapter.social.HttpJsonResponse
import java.io.IOException

/**
 * In-memory HttpJsonFetcher for unit tests — the suite has no network.
 * Records every URL requested so a test can assert how many times an adapter fetched.
 */
class FakeHttpJsonFetcher : HttpJsonFetcher {
    private val responses = mutableMapOf<String, HttpJsonResponse>()

    /** Every URL requested, in order. */
    val requestedUrls = mutableListOf<String>()

    /** When set, every request throws — a transport failure, not an HTTP status. */
    var shouldFail: Boolean = false

    /** When set, every request throws this instead — for failures other than a dead transport. */
    var failWith: Exception? = null

    fun clear() {
        responses.clear()
        requestedUrls.clear()
        shouldFail = false
        failWith = null
    }

    fun respondWith(
        url: String,
        body: String,
        statusCode: Int = 200,
    ) {
        responses[url] = HttpJsonResponse(statusCode, body)
    }

    fun callCount(url: String): Int = requestedUrls.count { it == url }

    override fun get(url: String): HttpJsonResponse {
        requestedUrls += url
        failWith?.let { throw it }
        if (shouldFail) throw IOException("Simulated transport failure for $url")
        return responses[url] ?: HttpJsonResponse(404, "")
    }
}
