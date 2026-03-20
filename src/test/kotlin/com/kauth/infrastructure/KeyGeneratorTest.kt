package com.kauth.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [KeyGenerator] — RSA key pair generation and PEM serialization.
 *
 * Tests cover: key pair generation, PEM format, round-trip encode/decode,
 * key uniqueness, and key size validation.
 */
class KeyGeneratorTest {
    // =========================================================================
    // generateRsaKeyPair
    // =========================================================================

    @Test
    fun `generateRsaKeyPair - returns KeyPairPem with correct keyId`() {
        val kp = KeyGenerator.generateRsaKeyPair("test-key-1")
        assertEquals("test-key-1", kp.keyId)
    }

    @Test
    fun `generateRsaKeyPair - public key is valid PEM format`() {
        val kp = KeyGenerator.generateRsaKeyPair("test-key-2")
        assertTrue(kp.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertTrue(kp.publicKeyPem.endsWith("-----END PUBLIC KEY-----"))
    }

    @Test
    fun `generateRsaKeyPair - private key is valid PEM format`() {
        val kp = KeyGenerator.generateRsaKeyPair("test-key-3")
        assertTrue(kp.privateKeyPem.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue(kp.privateKeyPem.endsWith("-----END PRIVATE KEY-----"))
    }

    @Test
    fun `generateRsaKeyPair - produces unique key pairs on each call`() {
        val kp1 = KeyGenerator.generateRsaKeyPair("key-a")
        val kp2 = KeyGenerator.generateRsaKeyPair("key-b")
        assertNotEquals(kp1.publicKeyPem, kp2.publicKeyPem, "Different calls should produce different public keys")
        assertNotEquals(kp1.privateKeyPem, kp2.privateKeyPem, "Different calls should produce different private keys")
    }

    // =========================================================================
    // decodePublicKey / decodePrivateKey — round-trip
    // =========================================================================

    @Test
    fun `decodePublicKey - round-trip from generated PEM produces valid RSA key`() {
        val kp = KeyGenerator.generateRsaKeyPair("round-trip-pub")
        val decoded = KeyGenerator.decodePublicKey(kp.publicKeyPem)
        assertEquals("RSA", decoded.algorithm)
        assertTrue(decoded.modulus.bitLength() >= 2048, "Key must be at least 2048-bit")
    }

    @Test
    fun `decodePrivateKey - round-trip from generated PEM produces valid RSA key`() {
        val kp = KeyGenerator.generateRsaKeyPair("round-trip-priv")
        val decoded = KeyGenerator.decodePrivateKey(kp.privateKeyPem)
        assertEquals("RSA", decoded.algorithm)
        assertTrue(decoded.modulus.bitLength() >= 2048, "Key must be at least 2048-bit")
    }

    @Test
    fun `public and private keys share the same modulus`() {
        val kp = KeyGenerator.generateRsaKeyPair("modulus-check")
        val pub = KeyGenerator.decodePublicKey(kp.publicKeyPem)
        val priv = KeyGenerator.decodePrivateKey(kp.privateKeyPem)
        assertEquals(pub.modulus, priv.modulus, "Public and private keys must share the same modulus")
    }

    @Test
    fun `generated key pair can sign and verify data`() {
        val kp = KeyGenerator.generateRsaKeyPair("sign-verify")
        val priv = KeyGenerator.decodePrivateKey(kp.privateKeyPem)
        val pub = KeyGenerator.decodePublicKey(kp.publicKeyPem)

        val data = "test payload".toByteArray()
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(priv)
        sig.update(data)
        val signature = sig.sign()

        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pub)
        verifier.update(data)
        assertTrue(verifier.verify(signature), "Signature produced by private key must verify with public key")
    }
}
