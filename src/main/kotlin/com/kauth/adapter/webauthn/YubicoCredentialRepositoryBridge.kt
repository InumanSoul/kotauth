package com.kauth.adapter.webauthn

import com.kauth.domain.port.WebAuthnCredentialRepository
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import java.security.MessageDigest
import java.util.Optional
import com.yubico.webauthn.data.ByteArray as YubiByteArray

/**
 * Bridges [WebAuthnCredentialRepository] into Yubico's [CredentialRepository] interface.
 *
 * User handles are not stored in the database — they are derived deterministically
 * from tenantId + userId + secretKey (same algorithm as [com.kauth.domain.service.WebAuthnService.deriveUserHandle]).
 * Yubico's [finishAssertion] verifies that the handle returned here matches the one
 * embedded in the authenticator's response; using a static placeholder causes the
 * "User handle does not own credential" assertion failure.
 */
class YubicoCredentialRepositoryBridge(
    private val repo: WebAuthnCredentialRepository,
    private val secretKey: String,
) : CredentialRepository {
    // Discoverable-only flow: no username lookup needed.
    override fun getCredentialIdsForUsername(username: String) = emptySet<PublicKeyCredentialDescriptor>()

    override fun getUserHandleForUsername(username: String): Optional<YubiByteArray> = Optional.empty()

    override fun getUsernameForUserHandle(userHandle: YubiByteArray): Optional<String> = Optional.empty()

    override fun lookup(
        credentialId: YubiByteArray,
        userHandle: YubiByteArray,
    ): Optional<RegisteredCredential> {
        val stored = repo.findByCredentialId(credentialId.base64Url) ?: return Optional.empty()
        return Optional.of(
            RegisteredCredential
                .builder()
                .credentialId(credentialId)
                .userHandle(userHandle)
                .publicKeyCose(YubiByteArray(stored.publicKeyCose))
                .signatureCount(stored.signCounter)
                .build(),
        )
    }

    override fun lookupAll(credentialId: YubiByteArray): Set<RegisteredCredential> {
        val stored = repo.findByCredentialId(credentialId.base64Url) ?: return emptySet()
        val userHandle = deriveUserHandle(stored.tenantId.value, stored.userId.value)
        return setOf(
            RegisteredCredential
                .builder()
                .credentialId(credentialId)
                .userHandle(YubiByteArray(userHandle))
                .publicKeyCose(YubiByteArray(stored.publicKeyCose))
                .signatureCount(stored.signCounter)
                .build(),
        )
    }

    /**
     * Must produce the same 32-byte handle as [com.kauth.domain.service.WebAuthnService.deriveUserHandle].
     * The algorithm is intentionally duplicated here to keep the adapter layer self-contained
     * and avoid a service dependency inside a repository bridge.
     */
    private fun deriveUserHandle(
        tenantId: Int,
        userId: Int,
    ): ByteArray {
        val input = "$tenantId:$userId:$secretKey".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input)
    }
}
