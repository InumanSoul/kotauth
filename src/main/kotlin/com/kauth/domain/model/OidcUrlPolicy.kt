package com.kauth.domain.model

import java.net.URI

/**
 * Whether a URL may be used to reach an OIDC issuer.
 *
 * OIDC Core §2 requires the issuer identifier to use `https`, and the requirement is load-bearing
 * rather than ceremonial: over plaintext an on-path attacker rewrites the discovery document *and*
 * the `issuer` inside it, so comparing the document's issuer to the configured one compares the
 * attacker's value with the attacker's value and passes. Everything downstream — the authorization
 * endpoint, the JWKS URI, every signature checked against the key found there — then rests on a
 * document the attacker wrote. The same applies to the endpoints a document publishes: an `http`
 * endpoint inside an `https` document is a downgrade nothing downstream can see.
 *
 * Loopback is the one carve-out, for local development against an IdP on the same host: traffic to
 * 127.0.0.1, ::1 or localhost never reaches a network, so there is no on-path position to occupy.
 * The host is matched exactly against [LOOPBACK_HOSTS] — `http://localhost.attacker.example` and
 * `http://127.0.0.1.attacker.example` are ordinary internet hosts and are refused.
 */
object OidcUrlPolicy {
    private const val HTTPS = "https"
    private const val HTTP = "http"

    /** Matched exactly. A suffix or prefix match would make any attacker-owned host loopback. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** True when [url] is an https URL, or an http URL whose host is exactly a loopback address. */
    fun isSecure(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            HTTPS -> true
            HTTP -> host in LOOPBACK_HOSTS
            else -> false
        }
    }

    /** Why [url] is not usable as [field], or null when it is. Wording is operator-facing. */
    fun problemWith(
        url: String,
        field: String,
    ): String? =
        if (isSecure(url)) {
            null
        } else {
            "The $field must be an https URL — http is accepted only for localhost during development."
        }
}
