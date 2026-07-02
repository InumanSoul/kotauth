package com.kauth.adapter.webauthn

import com.kauth.domain.port.WebAuthnCredentialRepository
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import java.util.Optional
import com.yubico.webauthn.data.ByteArray as YubiByteArray

class YubicoCredentialRepositoryBridge(
    private val repo: WebAuthnCredentialRepository,
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
        return setOf(
            RegisteredCredential
                .builder()
                .credentialId(credentialId)
                .userHandle(YubiByteArray(ByteArray(32)))
                .publicKeyCose(YubiByteArray(stored.publicKeyCose))
                .signatureCount(stored.signCounter)
                .build(),
        )
    }
}
