package com.kauth.domain.model

import java.time.Instant

/**
 * How a provider is talked to: a compiled-in OAuth2 adapter, or generic OIDC discovery.
 */
enum class ProviderKind(
    val value: String,
) {
    OAUTH2("oauth2"),
    OIDC("oidc"),
    ;

    companion object {
        fun of(value: String): ProviderKind? = entries.find { it.value == value }
    }
}

/**
 * Domain model for a per-tenant identity provider configuration.
 *
 * Each tenant may configure one entry per provider key (Google, GitHub, or any OIDC issuer).
 * The client_secret is stored encrypted at rest and decrypted into this field
 * at runtime — never persist the plain value, and never expose it on a read.
 */
data class IdentityProvider(
    val id: Int? = null,
    val tenantId: TenantId,
    val provider: ProviderKey,
    val clientId: String,
    val clientSecret: String, // decrypted at runtime — AES-256-GCM in DB
    val enabled: Boolean = true,
    val kind: ProviderKind = ProviderKind.OAUTH2,
    val displayName: String? = null,
    val issuer: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val jwksUri: String? = null,
    val scopes: String = DEFAULT_OIDC_SCOPES,
    val jitEnabled: Boolean = false,
    val jitAllowedDomains: List<String> = emptyList(),
    /**
     * Whether the issuer's `email` claim may be taken as verified.
     *
     * An absent `email_verified` claim reads as false, and some major issuers never emit it, so
     * without this JIT could not provision from them at all. The domain allowlist still applies:
     * this widens which addresses count as verified, never which domains are allowed.
     */
    val trustEmailClaim: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    // The generated toString() of a data class prints every field, this one's plaintext secret
    // included, and one interpolation of a whole row into a log line would be enough.
    override fun toString(): String = "IdentityProvider(id=$id, tenantId=$tenantId, provider=$provider, kind=$kind)"
}

const val DEFAULT_OIDC_SCOPES = "openid email profile"

/**
 * The redirect URI a brokered sign-in sends to the provider, and the one an operator has to
 * register there.
 *
 * One definition on purpose: the login flow and the admin page that tells an operator what to
 * register have to agree exactly, and a redirect URI the provider does not recognise is refused at
 * the provider — after setup looks finished — with nothing on our side able to detect it first.
 */
fun socialCallbackUrl(
    baseUrl: String,
    tenantSlug: String,
    provider: ProviderKey,
): String = "$baseUrl/t/$tenantSlug/auth/social/${provider.value}/callback"

/**
 * The callback URL with the provider key left as a placeholder.
 *
 * The add form has no key until the operator types one, and a callback they cannot read there is
 * a callback they must register twice at the issuer — once as a guess, once corrected.
 */
fun socialCallbackUrlTemplate(
    baseUrl: String,
    tenantSlug: String,
    placeholder: String = "provider-key",
): String = "$baseUrl/t/$tenantSlug/auth/social/$placeholder/callback"
