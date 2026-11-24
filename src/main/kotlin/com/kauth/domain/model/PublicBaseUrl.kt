package com.kauth.domain.model

import java.net.URI

/**
 * A workspace's public origin: scheme, host and optional port, nothing else. Pages keep their
 * `/t/{slug}` paths on it, so a path here would be ambiguous.
 */
object PublicBaseUrl {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    /** The normalized origin, or the reason [raw] is not one. */
    fun parse(raw: String): Result {
        val value = raw.trim().removeSuffix("/")
        val uri =
            runCatching { URI(value) }.getOrNull()
                ?: return Result.Invalid("The public domain must be a URL like https://auth.example.com.")
        val host = uri.host?.lowercase()
        if (uri.scheme == null || host.isNullOrBlank()) {
            return Result.Invalid("The public domain must be a URL like https://auth.example.com.")
        }
        val scheme = uri.scheme.lowercase()
        val local = host in LOCAL_HOSTS || host.endsWith(".localhost")
        if (scheme != "https" && !(scheme == "http" && local)) {
            return Result.Invalid("The public domain must use https.")
        }
        val beyondOrigin = listOf(uri.rawPath?.ifEmpty { null }, uri.rawQuery, uri.rawFragment, uri.rawUserInfo)
        if (beyondOrigin.any { it != null }) {
            return Result.Invalid("The public domain is an origin only: no path, query or credentials.")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.Valid("$scheme://$host$port")
    }

    sealed class Result {
        data class Valid(
            val origin: String,
        ) : Result()

        data class Invalid(
            val reason: String,
        ) : Result()
    }
}
