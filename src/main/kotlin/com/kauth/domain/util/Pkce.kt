package com.kauth.domain.util

import java.security.MessageDigest
import java.util.Base64

/**
 * PKCE (RFC 7636) from the relying-party side: a fresh verifier per authorization request, and
 * the challenge derived from it.
 *
 * `S256` only. The `plain` method sends the verifier itself on the front channel, which is the
 * one place the value is exposed, so it defends against nothing.
 */
object Pkce {
    /** The only challenge method this server sends. RFC 7636 §4.3. */
    const val CHALLENGE_METHOD_S256 = "S256"

    /** 32 random bytes as base64url without padding — 43 characters, within RFC 7636 §4.1's range. */
    fun newVerifier(): String = SecureTokens.randomBase64Url(VERIFIER_BYTES)

    /** `BASE64URL(SHA256(ASCII(verifier)))` — RFC 7636 §4.2. */
    fun challengeFor(verifier: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private const val VERIFIER_BYTES = 32
}
