package com.kauth.domain.model

import com.kauth.domain.util.sha256Hex

/**
 * The shape of a [AuditEventType.SOCIAL_LOGIN_FAILED] record: the detail keys and the closed set
 * of reason codes. Written by the just-in-time gate and by the callback route, read by the admin
 * diagnostics panel — one definition so a reader is never left on a key a writer stopped using.
 *
 * What a refusal may carry is deliberately narrow. Nothing here is a credential: no token, no ID
 * token, no authorization code, no client secret, no PKCE verifier. Nothing here identifies the
 * refused person either — the email's domain is what an operator repairs an allowlist with, the
 * local part is only what would turn this panel into a list of everyone who was turned away, and
 * the provider's subject identifier is the same directory key by another name. [reference] stands
 * in for both: derived from the identity, reversible to none of it, and stable enough that one
 * person retrying six times reads as one person rather than six.
 */
object BrokeredSignInFailure {
    const val PROVIDER = "provider"
    const val REASON = "reason"
    const val EMAIL_DOMAIN = "email_domain"
    const val IDP_ERROR_CODE = "idp_error_code"
    const val REFERENCE = "reference"

    const val EMAIL_NOT_VERIFIED = "email_not_verified"
    const val DOMAIN_NOT_ALLOWED = "domain_not_allowed"
    const val IDP_RETURNED_ERROR = "idp_returned_error"

    /** OAuth2 error codes are short ASCII tokens (RFC 6749 §4.1.2.1); the query is anyone's to write. */
    private val ERROR_CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")

    /**
     * The code the person is shown and the operator sees on the row.
     *
     * A truncated digest over the tenant, the provider and the provider's subject: one person is
     * one reference, two tenants never share one, and it reverses to nothing.
     */
    fun reference(
        tenantId: TenantId,
        provider: ProviderKey,
        providerUserId: String,
    ): String =
        sha256Hex("${tenantId.value}|${provider.value}|$providerUserId")
            .take(8)
            .uppercase()

    /** The domain half of an address, or null when there is no single unambiguous one. */
    fun emailDomainOf(email: String?): String? {
        val trimmed = email?.trim()?.lowercase() ?: return null
        if (trimmed.count { it == '@' } != 1) return null
        return trimmed.substringAfterLast('@').ifBlank { null }
    }

    /** The provider's error code, or null when what arrived was not shaped like one. */
    fun idpErrorCode(raw: String?): String? = raw?.trim()?.takeIf { ERROR_CODE.matches(it) }
}
