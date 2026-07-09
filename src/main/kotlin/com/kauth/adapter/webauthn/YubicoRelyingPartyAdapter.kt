package com.kauth.adapter.webauthn

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kauth.domain.port.AssertionResultData
import com.kauth.domain.port.RegisteredCredentialData
import com.kauth.domain.port.RelyingPartyAdapter
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import java.util.UUID
import com.yubico.webauthn.data.ByteArray as YubiByteArray

/**
 * Production implementation of [RelyingPartyAdapter] backed by the Yubico webauthn-server-core library.
 *
 * Stateless flow: Yubico requires the original [PublicKeyCredentialCreationOptions] /
 * [AssertionRequest] to verify responses. We serialize them to JSON at start-time
 * and store them in the encrypted session cookie. On finish, the JSON is passed
 * back and re-parsed via the corresponding fromJson factory method.
 *
 * excludeCredentialIds: passed by the service to prevent re-registering the same
 * credential. The Yubico [RelyingParty] populates the excludeCredentials list via
 * the [com.yubico.webauthn.CredentialRepository] bridge (Task 6); the parameter
 * is accepted here for future use or direct enforcement at the options level.
 */
class YubicoRelyingPartyAdapter(
    private val relyingParty: RelyingParty,
) : RelyingPartyAdapter {
    private val jsonMapper = ObjectMapper()

    @Suppress("UnusedParameter")
    override fun startRegistration(
        userHandle: ByteArray,
        username: String,
        displayName: String,
        excludeCredentialIds: List<String>,
    ): Pair<String, String> {
        val userIdentity =
            UserIdentity
                .builder()
                .name(username)
                .displayName(displayName)
                .id(YubiByteArray(userHandle))
                .build()

        val options =
            StartRegistrationOptions
                .builder()
                .user(userIdentity)
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria
                        .builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build(),
                ).build()

        val creationOptions = relyingParty.startRegistration(options)
        val json = creationOptions.toJson()
        val challenge = creationOptions.challenge.base64Url
        return json to challenge
    }

    override fun finishRegistration(
        creationOptionsJson: String,
        credentialResponseJson: String,
    ): RegisteredCredentialData {
        val request = PublicKeyCredentialCreationOptions.fromJson(creationOptionsJson)
        val credential = PublicKeyCredential.parseRegistrationResponseJson(credentialResponseJson)

        val result =
            try {
                relyingParty.finishRegistration(
                    FinishRegistrationOptions
                        .builder()
                        .request(request)
                        .response(credential)
                        .build(),
                )
            } catch (e: RegistrationFailedException) {
                throw IllegalStateException("Registration verification failed: ${e.message}", e)
            }

        val aaguidBytes = result.aaguid.bytes
        val aaguid =
            if (aaguidBytes.all { it == 0.toByte() }) {
                null
            } else {
                val bb = java.nio.ByteBuffer.wrap(aaguidBytes)
                UUID(bb.long, bb.long)
            }

        val transports = credential.response.transports.map { it.id }

        return RegisteredCredentialData(
            credentialId = result.keyId.id.base64Url,
            publicKeyCose = result.publicKeyCose.bytes,
            signCounter = result.signatureCount,
            aaguid = aaguid,
            transports = transports,
            backupEligible = result.isBackupEligible,
            backupState = result.isBackedUp,
        )
    }

    override fun startAssertion(): Pair<String, String> {
        val assertionRequest = relyingParty.startAssertion(StartAssertionOptions.builder().build())
        val json = assertionRequest.toCredentialsGetJson()
        val challenge = assertionRequest.publicKeyCredentialRequestOptions.challenge.base64Url
        return json to challenge
    }

    override fun finishAssertion(
        assertionRequestJson: String,
        credentialResponseJson: String,
    ): AssertionResultData {
        val browserTree = jsonMapper.readTree(assertionRequestJson)
        val innerNode = browserTree.get("publicKey") ?: browserTree
        val wrappedTree = jsonMapper.createObjectNode()
        wrappedTree.set<JsonNode>("publicKeyCredentialRequestOptions", innerNode)
        val request = AssertionRequest.fromJson(jsonMapper.writeValueAsString(wrappedTree))
        val credential = PublicKeyCredential.parseAssertionResponseJson(credentialResponseJson)

        val result =
            try {
                relyingParty.finishAssertion(
                    FinishAssertionOptions
                        .builder()
                        .request(request)
                        .response(credential)
                        .build(),
                )
            } catch (e: AssertionFailedException) {
                throw IllegalStateException("Assertion verification failed: ${e.message}", e)
            }

        return AssertionResultData(
            credentialId = result.credentialId.base64Url,
            userHandle = result.userHandle.bytes,
            newSignCounter = result.signatureCount,
            userVerified = result.isUserVerified,
        )
    }
}
