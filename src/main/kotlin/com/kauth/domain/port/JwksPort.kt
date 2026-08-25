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
}
