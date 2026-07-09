package com.kauth.adapter.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import com.yubico.webauthn.data.ByteArray as YubiByteArray

/**
 * Verifies that [YubicoRelyingPartyAdapter] serialises options in a shape the
 * browser's navigator.credentials API can decode. Registration returns the
 * unwrapped creation options ({rp, user, challenge, ...}). Assertion returns
 * Yubico's toCredentialsGetJson envelope ({publicKey: {challenge, rpId, ...}})
 * because AssertionRequest.toJson wraps fields under publicKeyCredentialRequestOptions
 * which the browser cannot consume. The bundle's decode helper handles both the
 * unwrapped and publicKey-envelope shapes.
 */
class YubicoRelyingPartyAdapterTest {
    private val rpIdentity =
        RelyingPartyIdentity
            .builder()
            .id("localhost")
            .name("Test")
            .build()

    private val emptyCredentialRepository =
        object : CredentialRepository {
            override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> = emptySet()

            override fun getUserHandleForUsername(username: String): Optional<YubiByteArray> = Optional.empty()

            override fun getUsernameForUserHandle(userHandle: YubiByteArray): Optional<String> = Optional.empty()

            override fun lookup(
                credentialId: YubiByteArray,
                userHandle: YubiByteArray,
            ): Optional<RegisteredCredential> = Optional.empty()

            override fun lookupAll(credentialId: YubiByteArray): Set<RegisteredCredential> = emptySet()
        }

    private val relyingParty =
        RelyingParty
            .builder()
            .identity(rpIdentity)
            .credentialRepository(emptyCredentialRepository)
            .origins(setOf("http://localhost:8080"))
            .allowOriginPort(false)
            .allowOriginSubdomain(false)
            .build()

    private val adapter = YubicoRelyingPartyAdapter(relyingParty)

    @Test
    fun `startRegistration produces JSON that round-trips through PublicKeyCredentialCreationOptions fromJson`() {
        val (json, _) =
            adapter.startRegistration(
                userHandle = ByteArray(32),
                username = "u",
                displayName = "U",
                excludeCredentialIds = emptyList(),
            )

        // If toCredentialsCreateJson() were used, fromJson would throw because the
        // top-level "rp" field would be null (wrapped under "publicKey").
        val parsed = PublicKeyCredentialCreationOptions.fromJson(json)

        assertNotNull(parsed)
        assertEquals("localhost", parsed.rp.id)
    }

    @Test
    fun `startAssertion produces JSON in the publicKey envelope shape the browser can decode`() {
        val (json, _) = adapter.startAssertion()

        val tree = ObjectMapper().readTree(json)
        val inner = tree.get("publicKey") ?: tree
        assertNotNull(inner.get("challenge"))
        assertEquals("localhost", inner.get("rpId").asText())
    }
}
