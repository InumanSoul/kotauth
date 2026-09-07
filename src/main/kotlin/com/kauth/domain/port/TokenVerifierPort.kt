package com.kauth.domain.port

import java.security.PublicKey

/** The untrusted header, read before any key is chosen. */
data class JwtHeader(
    val algorithm: String,
    val keyId: String?,
)

/** A verification failure. Carried in `Result.failure`; never thrown across the port boundary. */
class TokenVerifierFailure(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason {
        /** The string is not a JWT, or its header or payload is not decodable JSON. */
        MALFORMED,

        /** The signature did not verify against the supplied key. */
        SIGNATURE_INVALID,

        /** The key is of the wrong kind for the algorithm — an EC key for `RS256`, say. */
        KEY_MISMATCH,

        /** The caller named an algorithm this adapter cannot verify with. */
        UNSUPPORTED_ALGORITHM,
    }
}

/**
 * Outbound port — the JWT decoding and signature check that needs a JSON parser the domain
 * does not have.
 *
 * The split is deliberate: the adapter decodes and the domain decides. Nothing here reads
 * claim *policy* — issuer, audience, expiry and nonce are checked by
 * [com.kauth.domain.service.OidcTokenValidator], which also chooses the algorithm before ever
 * reaching [verifyAndDecode].
 */
interface TokenVerifierPort {
    /** Decodes the header without verifying anything. The caller must not trust it. */
    fun decodeHeader(token: String): Result<JwtHeader>

    /**
     * Verifies the signature with [key] under [algorithm] and returns the claims.
     *
     * [algorithm] is the caller's choice, never the token's own header — a verifier that took its
     * algorithm from the token it is verifying can be talked into HMAC-ing with a public key.
     */
    fun verifyAndDecode(
        token: String,
        key: PublicKey,
        algorithm: String,
    ): Result<Map<String, Any?>>
}
