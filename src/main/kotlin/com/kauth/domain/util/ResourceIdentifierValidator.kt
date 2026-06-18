package com.kauth.domain.util

import java.net.URI

private const val MAX_IDENTIFIER_LENGTH = 255
private val WHITESPACE = Regex("\\s")

fun validateResourceIdentifier(identifier: String): String? {
    if (identifier.isBlank()) return "identifier is required"
    if (identifier.length > MAX_IDENTIFIER_LENGTH) {
        return "identifier must be $MAX_IDENTIFIER_LENGTH characters or fewer"
    }
    if (WHITESPACE.containsMatchIn(identifier)) {
        return "identifier may not contain whitespace"
    }
    if (identifier.startsWith("http://", ignoreCase = true) ||
        identifier.startsWith("https://", ignoreCase = true)
    ) {
        return runCatching {
            val uri = URI(identifier)
            if (uri.host.isNullOrBlank()) "identifier must be an absolute URI with a host" else null
        }.getOrElse { "identifier must be a valid absolute URI" }
    }
    return null
}
