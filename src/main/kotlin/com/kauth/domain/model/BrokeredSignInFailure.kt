package com.kauth.domain.model

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The shape of a [AuditEventType.SOCIAL_LOGIN_FAILED] record: the detail keys and the closed set
 * of reason codes. Written by the just-in-time gate and by the callback route, read by the admin
 * diagnostics panel — one definition so a reader is never left on a key a writer stopped using.
 *
 * What a refusal may carry is deliberately narrow. Nothing here is a credential: no token, no ID
 * token, no authorization code, no client secret, no PKCE verifier. Nothing here identifies the
 * refused person either — the email's domain is what an operator repairs an allowlist with, the
 * local part is only what would turn this panel into a list of everyone who was turned away, and
 * the provider's subject identifier is the same directory key by another name.
 * [BrokeredReferenceHasher] stands in for both: derived from the identity under a key only this
 * instance holds, and stable enough that one person retrying six times reads as one person rather
 * than six.
 */
object BrokeredSignInFailure {
    const val PROVIDER = "provider"
    const val REASON = "reason"
    const val EMAIL_DOMAIN = "email_domain"
    const val IDP_ERROR_CODE = "idp_error_code"
    const val REFERENCE = "reference"

    const val EMAIL_NOT_VERIFIED = "email_not_verified"
    const val DOMAIN_NOT_ALLOWED = "domain_not_allowed"
    const val USERNAME_CONFLICT = "username_conflict"
    const val IDP_RETURNED_ERROR = "idp_returned_error"

    /** OAuth2 error codes are short ASCII tokens (RFC 6749 §4.1.2.1); the query is anyone's to write. */
    private val ERROR_CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    /** The domain half of an address, or null when there is no single unambiguous one. */
    fun emailDomainOf(email: String?): String? {
        val trimmed = email?.trim()?.lowercase() ?: return null
        if (trimmed.count { it == '@' } != 1) return null
        return trimmed.substringAfterLast('@').ifBlank { null }
    }

    /** The provider's error code, or null when what arrived was not shaped like one. */
    fun idpErrorCode(raw: String?): String? = raw?.trim()?.takeIf { ERROR_CODE.matches(it) }
}

/**
 * The reference the refused person is shown and the operator sees on the diagnostics row.
 *
 * A digest over the tenant, the provider and the provider's subject, truncated to eight hex
 * characters: one person is one reference and two tenants never share one.
 *
 * **It is keyed, and it has to be.** Eight hex is 32 bits over inputs that are small, public and
 * guessable — GitHub's `providerUserId` is a numeric account id — so an unkeyed digest is not
 * one-way in practice at all: anyone can hash candidate subjects offline until one matches a
 * reference they were shown, and recover the identity the panel exists to keep out of it. Under
 * HMAC with a key derived from `KAUTH_SECRET_KEY` there is nothing to enumerate against without
 * the key. The truncation still means two identities can collide, which costs an operator nothing:
 * the reference is a handle for a conversation, never an authorisation.
 */
class BrokeredReferenceHasher(
    rawSecretKey: String,
) {
    // Domain separation, as AuditChainHasher does it: this key is used for nothing else.
    private val key: ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$rawSecretKey|kauth/brokered-signin-reference/v1".toByteArray(Charsets.UTF_8))

    fun of(
        tenantId: TenantId,
        provider: ProviderKey,
        providerUserId: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac
            .doFinal("${tenantId.value}|${provider.value}|$providerUserId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
            .uppercase()
    }
}
