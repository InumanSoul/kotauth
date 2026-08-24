package com.kauth.adapter.web.plugin

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Per-call override for the global body-size ceiling. A route installs this attribute on the
 * call *before* it reads the body (e.g. at the top of the handler) to grant a bigger budget than
 * the rest of the API surface — currently only tenant backup import, whose body is an entire
 * tenant export.
 */
val MaxRequestBodyBytesAttr: AttributeKey<Long> = AttributeKey("MaxRequestBodyBytes")

/**
 * Bounds every request body to [defaultMaxBytes] bytes, or to the per-call override in
 * [MaxRequestBodyBytesAttr] when one is set. Must be installed before `ContentNegotiation` (or
 * any other plugin that transforms the body) so it sees the raw channel first — otherwise
 * [io.ktor.server.application.OnCallReceiveContext.transformBody] is a no-op because the subject
 * is no longer a `ByteReadChannel`.
 *
 * `Content-Length` is checked first only as a cheap rejection for declared-oversized requests —
 * it is never trusted on its own. A chunked request carries no `Content-Length`, so the real
 * enforcement below caps the bytes actually read off the wire: it reads at most `limit + 1`
 * bytes, and if that many arrive, the body is too large regardless of what any header claimed.
 */
fun requestBodySizeLimitPlugin(defaultMaxBytes: Long) =
    createApplicationPlugin("RequestBodySizeLimitPlugin") {
        onCallReceive { call ->
            val limit = call.attributes.getOrNull(MaxRequestBodyBytesAttr) ?: defaultMaxBytes

            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declaredLength != null && declaredLength > limit) {
                throw PayloadTooLargeException(limit)
            }

            transformBody { body ->
                val bytes = body.readRemaining(limit + 1).readByteArray()
                if (bytes.size.toLong() > limit) {
                    body.cancel(PayloadTooLargeException(limit))
                    throw PayloadTooLargeException(limit)
                }
                ByteReadChannel(bytes)
            }
        }
    }
