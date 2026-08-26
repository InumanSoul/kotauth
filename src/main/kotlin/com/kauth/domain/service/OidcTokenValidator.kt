package com.kauth.domain.service

import com.kauth.domain.port.JwksFailure
import com.kauth.domain.port.JwksPort
import com.kauth.domain.port.TokenVerifierFailure
import com.kauth.domain.port.TokenVerifierPort
import com.kauth.domain.service.OidcTokenFailure.Reason
import java.security.PublicKey
import java.time.Clock

/**
 * The claims a validated ID token carries.
 *
 * [subject] is the identity. Everything else is profile decoration, [email] very much included —
 * see [OidcTokenValidator] for why keying on it would be a defect.
 */
data class OidcClaims(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    val givenName: String?,
    val familyName: String?,
    val picture: String?,
)

/** An ID token validation failure. Carried in `Result.failure`; never thrown by the validator. */
class OidcTokenFailure(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason {
        /** The string is not a JWT, or a claim it must carry is absent or of the wrong type. */
        MALFORMED,

        /** The header names an algorithm outside the allowlist. */
        UNSUPPORTED_ALGORITHM,

        /** The header carries no `kid`, so no key can be chosen. */
        MISSING_KEY_ID,

        /** The issuer publishes no key for that `kid`, or its key set could not be read. */
        KEY_UNAVAILABLE,

        /** The signature did not verify against the issuer's key. */
        SIGNATURE_INVALID,

        /**
         * The issuer's key cannot check this token's algorithm at all — an EC key for `RS256`,
         * or an algorithm the verifier does not implement. Kept apart from [SIGNATURE_INVALID]
         * because it is a configuration fault, and an operator reading the log needs to tell it
         * from someone forging tokens at them.
         */
        KEY_UNUSABLE,

        /** `iss` is not the issuer we asked for. */
        ISSUER_MISMATCH,

        /** `aud` does not contain our client id. */
        AUDIENCE_MISMATCH,

        /** `azp` is present and is not our client id, or is absent from a multi-audience token. */
        AUTHORIZED_PARTY_MISMATCH,

        /** `exp` is in the past, beyond the skew allowance. */
        EXPIRED,

        /** `iat` is in the future, beyond the skew allowance. */
        ISSUED_IN_THE_FUTURE,

        /** `nonce` is absent or is not the one we sent. */
        NONCE_MISMATCH,

        /** `sub` is absent or blank, so the token identifies nobody. */
        SUBJECT_MISSING,
    }
}

/**
 * Decides whether an ID token is trustworthy, then reads the identity out of it.
 *
 * The order of the checks is the security property, not a style choice. The algorithm allowlist is
 * consulted on the *untrusted* header before any key is fetched and long before any signature is
 * checked, because a validator that takes its algorithm from the token it is verifying can be
 * handed `alg: HS256` over a signature computed with the issuer's RSA **public** key as the HMAC
 * secret — and that key is, by definition, public. Rejecting the algorithm first is the whole
 * defence; nothing later in this class can recover from getting it wrong.
 *
 * `sub` is the identity. `email` is never the identity: it is mutable at most providers, and an
 * address a person releases can be reassigned to someone else, who would then inherit the account.
 */
class OidcTokenValidator(
    private val jwks: JwksPort,
    private val verifier: TokenVerifierPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun validate(
        idToken: String,
        expectedIssuer: String,
        clientId: String,
        expectedNonce: String,
        jwksUri: String,
    ): Result<OidcClaims> {
        val header =
            verifier.decodeHeader(idToken).getOrElse {
                return failure(Reason.MALFORMED, "The ID token is not a JWT.")
            }

        // Step 2, and it must stay step 2: no key is fetched and no signature is checked until the
        // token's own claimed algorithm has been accepted.
        if (header.algorithm !in ALLOWED_ALGORITHMS) {
            return failure(Reason.UNSUPPORTED_ALGORITHM, "The ID token is signed with '${header.algorithm}'.")
        }
        val keyId = header.keyId ?: return failure(Reason.MISSING_KEY_ID, "The ID token header carries no kid.")

        val key = signingKey(jwksUri, keyId).getOrElse { return Result.failure(it) }
        val claims = verifiedClaims(idToken, key, header.algorithm).getOrElse { return Result.failure(it) }

        return claimPolicy(claims, expectedIssuer, clientId, expectedNonce)
    }

    private fun signingKey(
        jwksUri: String,
        keyId: String,
    ): Result<PublicKey> =
        jwks.signingKey(jwksUri, keyId).recoverCatching { cause ->
            val reason = (cause as? JwksFailure)?.reason?.name ?: "UNAVAILABLE"
            throw OidcTokenFailure(Reason.KEY_UNAVAILABLE, "No signing key for kid '$keyId' ($reason).")
        }

    private fun verifiedClaims(
        idToken: String,
        key: PublicKey,
        algorithm: String,
    ): Result<Map<String, Any?>> =
        verifier.verifyAndDecode(idToken, key, algorithm).recoverCatching { cause ->
            val reason =
                when ((cause as? TokenVerifierFailure)?.reason) {
                    TokenVerifierFailure.Reason.MALFORMED -> Reason.MALFORMED
                    TokenVerifierFailure.Reason.KEY_MISMATCH -> Reason.KEY_UNUSABLE
                    TokenVerifierFailure.Reason.UNSUPPORTED_ALGORITHM -> Reason.KEY_UNUSABLE
                    else -> Reason.SIGNATURE_INVALID
                }
            val message =
                if (reason == Reason.KEY_UNUSABLE) {
                    "The issuer's signing key cannot verify this token's algorithm."
                } else {
                    "The ID token signature could not be verified."
                }
            throw OidcTokenFailure(reason, message)
        }

    private fun claimPolicy(
        claims: Map<String, Any?>,
        expectedIssuer: String,
        clientId: String,
        expectedNonce: String,
    ): Result<OidcClaims> {
        if (claims.string("iss") != expectedIssuer) {
            return failure(Reason.ISSUER_MISMATCH, "The ID token was issued by someone else.")
        }

        val audiences = claims.audiences()
        if (clientId !in audiences) {
            return failure(Reason.AUDIENCE_MISMATCH, "The ID token is not addressed to this client.")
        }
        claims.authorizedPartyProblem(audiences, clientId)?.let { return failure(Reason.AUTHORIZED_PARTY_MISMATCH, it) }

        val now = clock.instant().epochSecond
        val expiresAt = claims.epochSeconds("exp") ?: return failure(Reason.MALFORMED, "The ID token carries no exp.")
        if (now > expiresAt + CLOCK_SKEW_SECONDS) {
            return failure(Reason.EXPIRED, "The ID token expired.")
        }
        val issuedAt = claims.epochSeconds("iat") ?: return failure(Reason.MALFORMED, "The ID token carries no iat.")
        if (issuedAt > now + CLOCK_SKEW_SECONDS) {
            return failure(Reason.ISSUED_IN_THE_FUTURE, "The ID token is issued in the future.")
        }

        // Compared in full; a prefix or suffix match would accept a nonce an attacker extended.
        if (claims.string("nonce") != expectedNonce) {
            return failure(Reason.NONCE_MISMATCH, "The ID token does not carry the nonce we sent.")
        }

        val subject =
            claims.string("sub")?.takeIf { it.isNotBlank() }
                ?: return failure(Reason.SUBJECT_MISSING, "The ID token carries no sub.")

        return Result.success(
            OidcClaims(
                subject = subject,
                email = claims.string("email"),
                emailVerified = claims.boolean("email_verified"),
                name = claims.string("name"),
                givenName = claims.string("given_name"),
                familyName = claims.string("family_name"),
                picture = claims.string("picture"),
            ),
        )
    }

    private fun Map<String, Any?>.string(name: String): String? = this[name] as? String

    /** `aud` is a string or an array of strings; anything else contributes no audience at all. */
    private fun Map<String, Any?>.audiences(): List<String> =
        when (val value = this["aud"]) {
            is String -> listOf(value)
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }

    /**
     * OIDC Core §3.1.3.7: `azp` must be present when the token has several audiences, and whenever
     * it is present at all it must name us.
     */
    private fun Map<String, Any?>.authorizedPartyProblem(
        audiences: List<String>,
        clientId: String,
    ): String? {
        val azp = string("azp")
        return when {
            azp == null && audiences.size > 1 -> "The ID token names several audiences but no azp."
            azp != null && azp != clientId -> "The ID token was authorized for another client."
            else -> null
        }
    }

    /** Numeric date claims arrive as `Long` from a JSON parser, but tolerate a numeric string. */
    private fun Map<String, Any?>.epochSeconds(name: String): Long? =
        when (val value = this[name]) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    /** Some issuers send `email_verified` as the string "true" rather than a JSON boolean. */
    private fun Map<String, Any?>.boolean(name: String): Boolean =
        when (val value = this[name]) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

    private fun <T> failure(
        reason: Reason,
        message: String,
    ): Result<T> = Result.failure(OidcTokenFailure(reason, message))

    companion object {
        /** Asymmetric signatures only, and only the two an OIDC issuer realistically needs. */
        val ALLOWED_ALGORITHMS: Set<String> = setOf("RS256", "ES256")

        /** Seconds of clock drift tolerated on `exp` and `iat`. */
        const val CLOCK_SKEW_SECONDS: Long = 60L
    }
}
