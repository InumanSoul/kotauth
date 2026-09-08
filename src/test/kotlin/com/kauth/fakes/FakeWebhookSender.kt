package com.kauth.fakes

import com.kauth.domain.port.WebhookRequest
import com.kauth.domain.port.WebhookSendResult
import com.kauth.domain.port.WebhookSenderPort

/**
 * Records every outbound webhook instead of sending it, and lets a test choose the outcome.
 *
 * Before the [WebhookSenderPort] extraction the transport was inside `WebhookService`, so a test
 * could only observe delivery rows — never the request itself, its headers, or its signature.
 */
class FakeWebhookSender(
    private var nextResult: WebhookSendResult = WebhookSendResult(200, true),
) : WebhookSenderPort {
    val sent = mutableListOf<WebhookRequest>()

    /** Outcome returned for every subsequent call. */
    fun respondWith(result: WebhookSendResult) {
        nextResult = result
    }

    /** Outcome chosen per call, by attempt index (0-based); falls back to [nextResult]. */
    var resultsByAttempt: List<WebhookSendResult> = emptyList()

    override fun post(request: WebhookRequest): WebhookSendResult {
        val index = sent.size
        sent += request
        return resultsByAttempt.getOrNull(index) ?: nextResult
    }

    fun clear() {
        sent.clear()
        resultsByAttempt = emptyList()
        nextResult = WebhookSendResult(200, true)
    }
}
