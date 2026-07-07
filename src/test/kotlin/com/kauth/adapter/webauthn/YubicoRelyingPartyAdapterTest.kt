package com.kauth.adapter.webauthn

import com.yubico.webauthn.AssertionRequest
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
 * Verifies that [YubicoRelyingPartyAdapter] serialises registration and assertion
 * options in the unwrapped JSON shape expected by the Yubico fromJson factories.
 *
 * Background: [PublicKeyCredentialCreationOptions.toCredentialsCreateJson] (and the
 * assertion equivalent) produce the browser-navigator envelope {"publicKey": {...}},
 * while fromJson expects the inner object {"rp": ..., "user": ...} directly. Using the
 * wrong serialiser causes "rp is marked non-null but is null" at finish time (500).
 * [PublicKeyCredentialCreationOptions.toJson] and [AssertionRequest.toJson] produce
 * the correct unwrapped shape — these tests lock that invariant in.
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
    fun `startAssertion produces JSON that round-trips through AssertionRequest fromJson`() {
        val (json, _) = adapter.startAssertion()

        // If toCredentialsGetJson() were used, fromJson would throw because the
        // assertion request fields would be wrapped under "publicKey".
        val parsed = AssertionRequest.fromJson(json)

        assertNotNull(parsed)
        assertNotNull(parsed.publicKeyCredentialRequestOptions.challenge)
    }
}
