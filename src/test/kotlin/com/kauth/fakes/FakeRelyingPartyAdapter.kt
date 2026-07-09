package com.kauth.fakes

import com.kauth.domain.port.AssertionResultData
import com.kauth.domain.port.RegisteredCredentialData
import com.kauth.domain.port.RelyingPartyAdapter
import java.util.UUID

/**
 * Fully in-memory fake [RelyingPartyAdapter] for domain unit tests.
 *
 * This fake does NOT wrap the real Yubico library — it returns canned values so tests can
 * exercise [com.kauth.domain.service.WebAuthnService] logic (tenant guards, counter checks,
 * audit event assertions) without requiring a real authenticator device or network.
 *
 * Deliberate simplification for v1.20.0: real Yubico cryptographic verification is exercised
 * in integration tests once real-device fixture capture is completed (follow-up task).
 *
 * Usage in tests:
 *   - Call [queueRegistration] to set the next [RegisteredCredentialData] returned by [finishRegistration].
 *   - Call [queueAssertion] to set the next [AssertionResultData] returned by [finishAssertion].
 *   - Set [throwOnFinishRegistration] / [throwOnFinishAssertion] to simulate verification failures.
 */
class FakeRelyingPartyAdapter : RelyingPartyAdapter {
    companion object {
        const val CANNED_CHALLENGE = "Y2FubmVkLWNoYWxsZW5nZQ"
        const val CANNED_CREATION_OPTIONS_JSON = """{"publicKey":{"challenge":"$CANNED_CHALLENGE"}}"""
        const val CANNED_ASSERTION_REQUEST_JSON = """{"publicKey":{"challenge":"$CANNED_CHALLENGE"}}"""
        const val CANNED_CREDENTIAL_ID = "Y2FubmVkLWNyZWRlbnRpYWwtaWQ"

        val DEFAULT_REGISTERED_CREDENTIAL =
            RegisteredCredentialData(
                credentialId = CANNED_CREDENTIAL_ID,
                publicKeyCose = ByteArray(77) { it.toByte() },
                signCounter = 0L,
                aaguid = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                transports = listOf("internal"),
                backupEligible = true,
                backupState = false,
            )

        val DEFAULT_ASSERTION_RESULT =
            AssertionResultData(
                credentialId = CANNED_CREDENTIAL_ID,
                userHandle = ByteArray(32) { 0x42 },
                newSignCounter = 1L,
                userVerified = true,
            )
    }

    var throwOnFinishRegistration: String? = null
    var throwOnFinishAssertion: String? = null

    private val registrationQueue = ArrayDeque<RegisteredCredentialData>()
    private val assertionQueue = ArrayDeque<AssertionResultData>()

    fun queueRegistration(data: RegisteredCredentialData = DEFAULT_REGISTERED_CREDENTIAL) {
        registrationQueue.addLast(data)
    }

    fun queueAssertion(data: AssertionResultData = DEFAULT_ASSERTION_RESULT) {
        assertionQueue.addLast(data)
    }

    override fun startRegistration(
        userHandle: ByteArray,
        username: String,
        displayName: String,
        excludeCredentialIds: List<String>,
    ): Pair<String, String> = CANNED_CREATION_OPTIONS_JSON to CANNED_CHALLENGE

    override fun finishRegistration(
        creationOptionsJson: String,
        credentialResponseJson: String,
    ): RegisteredCredentialData {
        throwOnFinishRegistration?.let { throw IllegalStateException(it) }
        return if (registrationQueue.isNotEmpty()) registrationQueue.removeFirst() else DEFAULT_REGISTERED_CREDENTIAL
    }

    override fun startAssertion(): Pair<String, String> = CANNED_ASSERTION_REQUEST_JSON to CANNED_CHALLENGE

    override fun finishAssertion(
        assertionRequestJson: String,
        credentialResponseJson: String,
    ): AssertionResultData {
        throwOnFinishAssertion?.let { throw IllegalStateException(it) }
        return if (assertionQueue.isNotEmpty()) assertionQueue.removeFirst() else DEFAULT_ASSERTION_RESULT
    }
}
