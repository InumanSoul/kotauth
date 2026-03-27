package com.kauth.adapter.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Shared OAuth utilities for admin and portal route handlers.
// PKCE (RFC 7636) generation and JWT payload decoding used by both
// the admin console and the self-service portal OAuth flows.

/** Generates a cryptographically random PKCE code verifier (43 chars, base64url, no padding). */
internal fun generatePkceVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Computes the S256 PKCE code challenge from a verifier. */
internal fun generatePkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Decodes a JWT's payload section (base64url) into a flat string map.
 *
 * Uses kotlinx.serialization for correct JSON parsing — handles strings,
 * numbers, booleans, and nested objects (serialized via toString).
 *
 * This does NOT verify the JWT signature. Only use on tokens received
 * from the local authorization server via a trusted code path.
 */
internal fun decodeJwtPayload(jwt: String): Map<String, String> {
    return try {
        val parts = jwt.split(".")
        if (parts.size < 2) return emptyMap()
        val payload =
            String(
                Base64.getUrlDecoder().decode(
                    parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                ),
                Charsets.UTF_8,
            )
        val jsonElement = Json.parseToJsonElement(payload)
        val obj = jsonElement as? JsonObject ?: return emptyMap()
        obj.entries.associate { (k, v) ->
            k to
                when (v) {
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
