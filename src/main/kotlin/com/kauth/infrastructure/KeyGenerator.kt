package com.kauth.infrastructure

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * RSA key pair generation and PEM serialization utilities.
 *
 * Generates 2048-bit RSA key pairs for RS256 JWT signing.
 * Keys are stored as PEM strings in the database (tenant_keys table).
 *
 * Security notes:
 *   - 2048-bit RSA is the minimum acceptable for production (NIST recommends 2048-4096).
 *   - Private keys are encrypted at rest with AES-256-GCM via EncryptionService.
 *   - Key rotation: generate a new key pair, add it (enabled=true), then disable the old
 *     one after all tokens signed with the old key have expired.
 */
object KeyGenerator {
    private const val KEY_SIZE = 2048
    private const val ALGORITHM = "RSA"

    data class KeyPairPem(
        val keyId: String,
        val publicKeyPem: String,
        val privateKeyPem: String,
    )

    /**
     * Generates a new RSA-2048 key pair and returns both keys as PEM strings.
     * [keyId] is the "kid" field published in the JWKS endpoint.
     */
    fun generateRsaKeyPair(keyId: String): KeyPairPem {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        generator.initialize(KEY_SIZE)
        val keyPair = generator.generateKeyPair()

        return KeyPairPem(
            keyId = keyId,
            publicKeyPem = encodePublicKey(keyPair.public as RSAPublicKey),
            privateKeyPem = encodePrivateKey(keyPair.private as RSAPrivateKey),
        )
    }

    /**
     * Decodes a PEM-encoded RSA public key back to [RSAPublicKey].
     */
    fun decodePublicKey(pem: String): RSAPublicKey {
        val decoded = Base64.getDecoder().decode(stripPemHeaders(pem))
        val spec = X509EncodedKeySpec(decoded)
        return KeyFactory.getInstance(ALGORITHM).generatePublic(spec) as RSAPublicKey
    }

    /**
     * Decodes a PEM-encoded RSA private key back to [RSAPrivateKey].
     */
    fun decodePrivateKey(pem: String): RSAPrivateKey {
        val decoded = Base64.getDecoder().decode(stripPemHeaders(pem))
        val spec = PKCS8EncodedKeySpec(decoded)
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(spec) as RSAPrivateKey
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun encodePublicKey(key: RSAPublicKey): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
            "\n-----END PUBLIC KEY-----"

    private fun encodePrivateKey(key: RSAPrivateKey): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
            "\n-----END PRIVATE KEY-----"

    private fun stripPemHeaders(pem: String): String =
        pem
            .lines()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
}
