package com.kauth.fakes

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.domain.port.JwksFailure
import com.kauth.domain.port.JwksPort
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * An in-memory OIDC issuer for tests — it mints ID tokens with real signatures and publishes the
 * keys that verify them, so validation runs end to end with no network and no Docker.
 *
 * It doubles as the [JwksPort] the validator consults, and records every key lookup so a test can
 * assert that a token was refused *before* any key was fetched.
 */
class FakeOidcIssuer(
    val issuer: String = "https://issuer.example",
    val jwksUri: String = "https://issuer.example/jwks",
    private val clock: Clock = Clock.systemUTC(),
) : JwksPort {
    /** How a minted token is signed. Only [OWN_RSA_KEY] and [OWN_EC_KEY] should ever validate. */
    enum class Signing {
        OWN_RSA_KEY,
        OWN_EC_KEY,

        /** A key pair this issuer never publishes — a forged signature. */
        UNPUBLISHED_KEY,

        /** `alg: none`, no signature at all. */
        NONE,

        /**
         * `alg: HS256`, HMAC-ed with this issuer's RSA public key as the secret — the algorithm
         * confusion attack. Anyone can compute it, because the key is public by design.
         */
        PUBLIC_KEY_AS_HMAC_SECRET,
    }

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val unpublishedRsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val ec =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    val rsaKeyId: String = "rsa-key-1"
    val ecKeyId: String = "ec-key-1"

    /** Every (jwksUri, kid) the validator asked for, in order. */
    val keyLookups = mutableListOf<Pair<String, String>>()

    override fun signingKey(
        jwksUri: String,
        kid: String,
    ): Result<PublicKey> {
        keyLookups += jwksUri to kid
        if (jwksUri != this.jwksUri) {
            return Result.failure(JwksFailure(JwksFailure.Reason.FETCH_FAILED, "No key set at $jwksUri."))
        }
        return when (kid) {
            rsaKeyId -> Result.success(rsa.public)
            ecKeyId -> Result.success(ec.public)
            else -> Result.failure(JwksFailure(JwksFailure.Reason.UNKNOWN_KID, "No key '$kid' at $jwksUri."))
        }
    }

    /** The two keys this issuer publishes — the RSA and the EC one. The unpublished pair is not one. */
    override fun verificationKeyCount(jwksUri: String): Result<Int> =
        if (jwksUri == this.jwksUri) {
            Result.success(PUBLISHED_KEYS)
        } else {
            Result.failure(JwksFailure(JwksFailure.Reason.FETCH_FAILED, "No key set at $jwksUri."))
        }

    fun idToken(
        subject: String? = DEFAULT_SUBJECT,
        issuer: String? = this.issuer,
        audience: List<String> = listOf(DEFAULT_AUDIENCE),
        authorizedParty: String? = null,
        nonce: String? = DEFAULT_NONCE,
        issuedAt: Instant? = clock.instant(),
        expiresAt: Instant? = clock.instant().plusSeconds(300),
        keyId: String? = rsaKeyId,
        profile: Map<String, Any?> = emptyMap(),
        signedWith: Signing = Signing.OWN_RSA_KEY,
    ): String {
        val builder = JWT.create()
        keyId?.let { builder.withKeyId(it) }
        subject?.let { builder.withSubject(it) }
        issuer?.let { builder.withIssuer(it) }
        if (audience.isNotEmpty()) builder.withAudience(*audience.toTypedArray())
        authorizedParty?.let { builder.withClaim("azp", it) }
        nonce?.let { builder.withClaim("nonce", it) }
        issuedAt?.let { builder.withIssuedAt(it) }
        expiresAt?.let { builder.withExpiresAt(it) }
        profile.forEach { (name, value) -> builder.putClaim(name, value) }
        return builder.sign(algorithmFor(signedWith))
    }

    private fun JWTCreator.Builder.putClaim(
        name: String,
        value: Any?,
    ) {
        when (value) {
            is String -> withClaim(name, value)
            is Boolean -> withClaim(name, value)
            is Long -> withClaim(name, value)
            is Int -> withClaim(name, value)
            is List<*> -> withClaim(name, value)
            null -> Unit
            else -> error("FakeOidcIssuer cannot mint a claim of type ${value.javaClass.name}")
        }
    }

    private fun algorithmFor(signing: Signing): Algorithm =
        when (signing) {
            Signing.OWN_RSA_KEY -> Algorithm.RSA256(rsa.public as RSAPublicKey, rsa.private as RSAPrivateKey)
            Signing.OWN_EC_KEY -> Algorithm.ECDSA256(ec.public as ECPublicKey, ec.private as ECPrivateKey)
            Signing.UNPUBLISHED_KEY ->
                Algorithm.RSA256(
                    unpublishedRsa.public as RSAPublicKey,
                    unpublishedRsa.private as RSAPrivateKey,
                )
            Signing.NONE -> Algorithm.none()
            Signing.PUBLIC_KEY_AS_HMAC_SECRET ->
                Algorithm.HMAC256(Base64.getEncoder().encodeToString(rsa.public.encoded))
        }

    companion object {
        const val DEFAULT_SUBJECT = "issuer-subject-1"
        const val DEFAULT_AUDIENCE = "kotauth-client"
        const val DEFAULT_NONCE = "nonce-from-our-session"

        /** The RSA and EC keys this issuer publishes. The unpublished forgery pair is not one of them. */
        const val PUBLISHED_KEYS = 2
    }
}
