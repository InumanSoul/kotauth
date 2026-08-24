package com.kauth.adapter.web.plugin

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.io.Buffer
import kotlinx.io.Source

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
 * enforcement wraps the channel in [LimitingByteReadChannel], which counts every byte actually
 * handed to the reader (JSON deserialization, multipart parsing, …) and fails once the running
 * total passes the limit — regardless of what any header claimed, and without ever buffering the
 * whole body: a 100 MiB import stays bounded by the underlying channel's chunk size, not by the
 * configured limit. An earlier version buffered up to `limit + 1` bytes into one contiguous
 * array before checking; that peaked at ~2x the configured limit in resident memory, which is
 * fine at a 2 MiB default but not at a 100 MiB import ceiling on a small container heap.
 */
fun requestBodySizeLimitPlugin(defaultMaxBytes: Long) =
    createApplicationPlugin("RequestBodySizeLimitPlugin") {
        onCallReceive { call ->
            val limit = call.attributes.getOrNull(MaxRequestBodyBytesAttr) ?: defaultMaxBytes

            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declaredLength != null && declaredLength > limit) {
                throw PayloadTooLargeException(limit)
            }

            transformBody { body -> LimitingByteReadChannel(body, limit) }
        }
    }

/**
 * Streams [delegate] through unchanged while counting every byte transferred out of it, and
 * fails past [limit]. Modeled directly on Ktor's own `CountedByteReadChannel` (io.ktor.utils.io) —
 * same technique of owning a small local [Buffer] and pulling one delegate chunk into it at a
 * time — so it never holds more than one chunk beyond what the reader hasn't consumed yet, unlike
 * materialising the entire body up front.
 */
@OptIn(InternalAPI::class)
private class LimitingByteReadChannel(
    private val delegate: ByteReadChannel,
    private val limit: Long,
) : ByteReadChannel {
    private val buffer = Buffer()
    private var transferred = 0L

    override val closedCause: Throwable? get() = delegate.closedCause

    override val isClosedForRead: Boolean
        get() = buffer.exhausted() && delegate.isClosedForRead

    @InternalAPI
    override val readBuffer: Source
        get() {
            transferFromDelegate()
            return buffer
        }

    override suspend fun awaitContent(min: Int): Boolean {
        if (buffer.size >= min) return true
        if (delegate.awaitContent(min)) {
            transferFromDelegate()
            return true
        }
        return false
    }

    override fun cancel(cause: Throwable?) {
        delegate.cancel(cause)
        buffer.close()
    }

    private fun transferFromDelegate() {
        val appended = buffer.transferFrom(delegate.readBuffer)
        transferred += appended
        if (transferred > limit) {
            val tooLarge = PayloadTooLargeException(limit)
            delegate.cancel(tooLarge)
            buffer.close()
            throw tooLarge
        }
    }
}
