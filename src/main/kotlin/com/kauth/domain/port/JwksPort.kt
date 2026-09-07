package com.kauth.domain.port

import java.security.PublicKey

/** A JWKS failure. Carried in `Result.failure`; never thrown across the port boundary. */
class JwksFailure(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason {
        /** The key set could not be retrieved. */
        FETCH_FAILED,

        /** The far end sent more than a key set's worth of bytes. */
        RESPONSE_TOO_LARGE,

        /** The JWKS URI is not an https URL. */
        INSECURE_URL,

        /** The key set parsed but carries no usable signing key. */
        MALFORMED,

        /** No key in the set carries the requested kid, and the refetch budget is spent or exhausted. */
        UNKNOWN_KID,
    }
}

/** Outbound port — the signing keys an issuer publishes at its JWKS URI. */
interface JwksPort {
    /** The signing key for [kid], refetching at most once per window on an unknown kid. */
    fun signingKey(
        jwksUri: String,
        kid: String,
    ): Result<PublicKey>

    /**
     * How many keys at [jwksUri] we could actually verify a signature with, read fresh.
     *
     * A setup probe wants what the issuer serves now, not what a cache holds, and it must not
     * populate that cache either — a probe is a read that changes nothing a sign-in depends on.
     */
    fun verificationKeyCount(jwksUri: String): Result<Int>
}
