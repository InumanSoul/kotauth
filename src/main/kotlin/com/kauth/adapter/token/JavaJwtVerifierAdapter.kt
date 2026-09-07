package com.kauth.adapter.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.domain.port.JwtHeader
import com.kauth.domain.port.TokenVerifierFailure
import com.kauth.domain.port.TokenVerifierFailure.Reason
import com.kauth.domain.port.TokenVerifierPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * [TokenVerifierPort] over `com.auth0:java-jwt`.
 *
 * Exists so that library — and the JSON parsing a JWT needs — stays out of `domain/`. It decodes
 * and checks signatures; it decides nothing. Claim policy lives in
 * [com.kauth.domain.service.OidcTokenValidator].
 *
 * The signature is checked with [Algorithm.verify] rather than `JWT.require(...)`, because the
 * latter also applies its own expiry policy. Policy belongs in one place, and that place is the
 * domain.
 */
class JavaJwtVerifierAdapter : TokenVerifierPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    override fun decodeHeader(token: String): Result<JwtHeader> =
        try {
            val decoded = JWT.decode(token)
            Result.success(JwtHeader(decoded.algorithm ?: "", decoded.keyId))
        } catch (e: Exception) {
            log.debug("ID token header did not decode: {}", e.javaClass.simpleName)
            Result.failure(TokenVerifierFailure(Reason.MALFORMED, "The token is not a JWT."))
        }

    override fun verifyAndDecode(
        token: String,
        key: PublicKey,
        algorithm: String,
    ): Result<Map<String, Any?>> {
        val verifier = algorithmFor(algorithm, key).getOrElse { return Result.failure(it) }
        val decoded =
            try {
                JWT.decode(token)
            } catch (e: Exception) {
                log.debug("ID token did not decode: {}", e.javaClass.simpleName)
                return failure(Reason.MALFORMED, "The token is not a JWT.")
            }

        try {
            verifier.verify(decoded)
        } catch (e: Exception) {
            log.debug("ID token signature rejected: {}", e.javaClass.simpleName)
            return failure(Reason.SIGNATURE_INVALID, "The token signature does not verify against the issuer's key.")
        }

        return try {
            Result.success(claimsOf(decoded.payload))
        } catch (e: Exception) {
            log.debug("ID token payload did not parse: {}", e.javaClass.simpleName)
            failure(Reason.MALFORMED, "The token payload is not a JSON object.")
        }
    }

    /** Only the algorithms the domain allowlists, and only when the key is of the matching kind. */
    private fun algorithmFor(
        algorithm: String,
        key: PublicKey,
    ): Result<Algorithm> =
        when (algorithm) {
            "RS256" ->
                (key as? RSAPublicKey)?.let { Result.success(Algorithm.RSA256(it)) }
                    ?: failure(Reason.KEY_MISMATCH, "RS256 needs an RSA key; the issuer published a ${key.algorithm}.")
            "ES256" ->
                (key as? ECPublicKey)?.let { Result.success(Algorithm.ECDSA256(it)) }
                    ?: failure(Reason.KEY_MISMATCH, "ES256 needs an EC key; the issuer published a ${key.algorithm}.")
            else -> failure(Reason.UNSUPPORTED_ALGORITHM, "This verifier cannot check a $algorithm signature.")
        }

    private fun claimsOf(base64UrlPayload: String): Map<String, Any?> =
        json
            .parseToJsonElement(String(Base64.getUrlDecoder().decode(base64UrlPayload)))
            .let { it as JsonObject }
            .mapValues { (_, value) -> kotlinValueOf(value) }

    private fun kotlinValueOf(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonPrimitive ->
                if (element.isString) {
                    element.content
                } else {
                    element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
                }
            is JsonArray -> element.map { kotlinValueOf(it) }
            is JsonObject -> element.mapValues { (_, value) -> kotlinValueOf(value) }
        }

    private fun <T> failure(
        reason: Reason,
        message: String,
    ): Result<T> = Result.failure(TokenVerifierFailure(reason, message))
}
