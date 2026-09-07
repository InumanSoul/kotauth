package com.kauth.domain.service

import java.net.URI

/**
 * Matches a requested `redirect_uri` against a client's registered URIs.
 *
 * The default is exact string comparison, as RFC 6749 §3.1.2.3 requires. The one exception
 * is the loopback interface: RFC 8252 §7.3 states the authorization server MUST allow any
 * port for loopback redirect URIs, because a native app binds an ephemeral port at login
 * time and cannot know it in advance.
 *
 * The exception is deliberately narrow — see [ADR-22](../../../../../../docs/adr/ADR-22-rfc8252-loopback-redirect-matching.md):
 *
 *  - `http` scheme only, on the literal loopback addresses `127.0.0.1` and `[::1]`.
 *    `localhost` is excluded on purpose: it resolves through the name service and can be
 *    pointed elsewhere by a hosts-file entry or a DNS answer (RFC 8252 §8.3).
 *  - Only the port is ignored. Scheme, host, path, query and fragment must all match, and
 *    a URI carrying userinfo never matches.
 *  - Gated behind [allowAnyLoopbackPort], which callers set for public clients only.
 *
 * This governs which URIs a client may be *sent* to. It is not used to check the
 * `redirect_uri` presented at the token endpoint against the one stored on the authorization
 * code — that comparison stays exact, so a code issued for one port cannot be redeemed
 * while claiming another.
 */
internal object RedirectUriMatcher {
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "[::1]", "::1")

    fun matches(
        registeredUris: List<String>,
        requestedUri: String,
        allowAnyLoopbackPort: Boolean,
    ): Boolean {
        if (registeredUris.contains(requestedUri)) return true
        if (!allowAnyLoopbackPort) return false

        val requested = loopbackKeyOf(requestedUri) ?: return false
        return registeredUris.any { loopbackKeyOf(it) == requested }
    }

    /**
     * A comparison key for a loopback URI with the port removed, or null when the URI is not
     * an `http` loopback URI we are willing to treat port-agnostically.
     */
    private fun loopbackKeyOf(raw: String): String? {
        val uri =
            try {
                URI(raw)
            } catch (_: Exception) {
                return null
            }

        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        // Userinfo would let `http://evil@127.0.0.1/cb` sit alongside a registered URI that
        // has none; refuse rather than deciding what it should mean.
        if (uri.userInfo != null) return null

        val host = uri.host ?: return null
        if (host.lowercase() !in LOOPBACK_HOSTS) return null

        // Port deliberately absent from the key — that is the whole exception.
        return buildString {
            append("http://")
            append(host.lowercase())
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
            uri.rawFragment?.let {
                append('#')
                append(it)
            }
        }
    }
}
