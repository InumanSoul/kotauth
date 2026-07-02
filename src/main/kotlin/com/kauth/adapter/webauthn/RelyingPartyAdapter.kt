package com.kauth.adapter.webauthn

import java.util.UUID

/**
 * Isolates Yubico webauthn-server-core types from the domain layer.
 * Concrete implementation ([YubicoRelyingPartyAdapter]) wraps [com.yubico.webauthn.RelyingParty].
 */
interface RelyingPartyAdapter {
    /**
     * Build creation-options JSON for a new credential.
     * Returns (publicKeyOptionsJson, challenge base64url).
     * The caller must persist publicKeyOptionsJson in the session — it is required
     * to reconstruct the original request when [finishRegistration] is called.
     */
    fun startRegistration(
        userHandle: ByteArray,
        username: String,
        displayName: String,
        excludeCredentialIds: List<String>,
    ): Pair<String, String>

    /**
     * Verify a registration response.
     * [creationOptionsJson] is the full JSON returned by [startRegistration],
     * stored in the session and echoed back here so the adapter can reconstruct
     * the original request for cryptographic verification.
     * Returns parsed credential fields on success, throws [IllegalStateException] on failure.
     */
    fun finishRegistration(
        creationOptionsJson: String,
        credentialResponseJson: String,
    ): RegisteredCredentialData

    /**
     * Build assertion-options JSON for a discoverable-credential (passkey) login.
     * Returns (publicKeyOptionsJson, challenge base64url).
     * No username is required — the authenticator selects the credential.
     */
    fun startAssertion(): Pair<String, String>

    /**
     * Verify an assertion response.
     * Yubico's [com.yubico.webauthn.RelyingParty.finishAssertion] internally calls the
     * injected [com.yubico.webauthn.CredentialRepository] (implemented by
     * YubicoCredentialRepositoryBridge in Task 6) to fetch the stored credential during
     * verification. The service does NOT pre-fetch.
     * [assertionRequestJson] is the full JSON returned by [startAssertion], stored in the session.
     */
    fun finishAssertion(
        assertionRequestJson: String,
        credentialResponseJson: String,
    ): AssertionResultData
}

data class RegisteredCredentialData(
    val credentialId: String,
    val publicKeyCose: ByteArray,
    val signCounter: Long,
    val aaguid: UUID?,
    val transports: List<String>,
    val backupEligible: Boolean,
    val backupState: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegisteredCredentialData) return false
        return credentialId == other.credentialId &&
            publicKeyCose.contentEquals(other.publicKeyCose) &&
            signCounter == other.signCounter &&
            aaguid == other.aaguid &&
            transports == other.transports &&
            backupEligible == other.backupEligible &&
            backupState == other.backupState
    }

    override fun hashCode(): Int {
        var result = credentialId.hashCode()
        result = 31 * result + publicKeyCose.contentHashCode()
        result = 31 * result + signCounter.hashCode()
        result = 31 * result + (aaguid?.hashCode() ?: 0)
        result = 31 * result + transports.hashCode()
        result = 31 * result + backupEligible.hashCode()
        result = 31 * result + backupState.hashCode()
        return result
    }
}

data class AssertionResultData(
    val credentialId: String,
    val userHandle: ByteArray,
    val newSignCounter: Long,
    val userVerified: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssertionResultData) return false
        return credentialId == other.credentialId &&
            userHandle.contentEquals(other.userHandle) &&
            newSignCounter == other.newSignCounter &&
            userVerified == other.userVerified
    }

    override fun hashCode(): Int {
        var result = credentialId.hashCode()
        result = 31 * result + userHandle.contentHashCode()
        result = 31 * result + newSignCounter.hashCode()
        result = 31 * result + userVerified.hashCode()
        return result
    }
}
