package com.kauth.domain.port

/**
 * Delivers one webhook request to an operator-supplied URL.
 *
 * The transport lives behind this port so the domain does not open sockets. `WebhookService`
 * decides *what* to send — payload envelope, HMAC signature, retry schedule — and this decides
 * *how* it travels.
 *
 * The URL comes from an operator and is therefore untrusted: an implementation is the right
 * place for SSRF defence (refusing loopback, link-local and private ranges, capping redirects)
 * and for timeouts, none of which the domain can express.
 *
 * Implementations must not throw. A transport failure is a delivery outcome, not an exception —
 * `WebhookService` records it and schedules a retry.
 */
interface WebhookSenderPort {
    fun post(request: WebhookRequest): WebhookSendResult
}

/**
 * One outbound webhook. [headers] already carries the signature and event headers the domain
 * computed; the sender adds only transport concerns such as `Content-Length`.
 */
data class WebhookRequest(
    val url: String,
    val payload: String,
    val headers: Map<String, String>,
)

/**
 * @param httpStatus the response code, or null when the request never produced one.
 * @param success true only for a 2xx. Anything else, including a transport failure, is a retry.
 */
data class WebhookSendResult(
    val httpStatus: Int?,
    val success: Boolean,
)
