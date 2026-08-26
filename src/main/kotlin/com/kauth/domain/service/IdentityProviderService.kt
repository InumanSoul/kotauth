package com.kauth.domain.service

import com.kauth.domain.model.DEFAULT_OIDC_SCOPES
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.OidcUrlPolicy
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.IdentityProviderRepository
import java.net.IDN
import java.net.URI
import java.time.Instant

private const val MAX_CLIENT_ID = 255
private const val MAX_CLIENT_SECRET = 512
private const val MAX_DISPLAY_NAME = 64
private const val MAX_ISSUER = 255
private const val MAX_ENDPOINT = 512
private const val MAX_SCOPES = 255
private const val MAX_DOMAIN = 253

private val WHITESPACE = Regex("\\s+")

// Dot-separated LDH labels and nothing else. The gate matches a domain exactly, so a wildcard or a
// bare label would save cleanly and then match no address ever, with nothing telling the operator.
private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
private val URL_SCHEMES = setOf("http", "https")

private const val KEY_IMMUTABLE =
    "The provider key is fixed once saved — accounts already linked to this provider point at it. " +
        "Moving them to a different key is a migration in its own right."

/**
 * The single validation point for per-tenant identity provider configuration: the admin UI
 * and the REST API both go through here rather than writing the repository directly.
 *
 * The returned model carries the decrypted [IdentityProvider.clientSecret]. It is a write-only
 * field by contract — callers accept it on write and must never render or serialise it back.
 */
class IdentityProviderService(
    private val repository: IdentityProviderRepository,
) {
    fun list(tenantId: TenantId): List<IdentityProvider> = repository.findAllByTenant(tenantId)

    fun get(
        tenantId: TenantId,
        key: ProviderKey,
    ): IdentityProvider? = repository.findByTenantAndProvider(tenantId, key)

    /**
     * Creates or updates the configuration for [key]. Pass [id] to target a stored row
     * explicitly; the key of that row may not change, since every social_accounts row
     * points back at it.
     *
     * A null or blank [clientSecret] keeps the stored secret on update, and is rejected on create.
     */
    @Suppress("LongParameterList")
    fun save(
        tenantId: TenantId,
        key: ProviderKey,
        clientId: String,
        clientSecret: String?,
        id: Int? = null,
        kind: ProviderKind = ProviderKind.OAUTH2,
        enabled: Boolean = true,
        displayName: String? = null,
        issuer: String? = null,
        authorizationEndpoint: String? = null,
        tokenEndpoint: String? = null,
        jwksUri: String? = null,
        scopes: String = DEFAULT_OIDC_SCOPES,
        jitEnabled: Boolean = false,
        jitAllowedDomains: List<String> = emptyList(),
    ): AdminResult<IdentityProvider> {
        val existing =
            when (val target = resolveTarget(tenantId, key, id)) {
                is AdminResult.Failure -> return target
                is AdminResult.Success -> target.value
            }

        val draft =
            IdentityProvider(
                id = existing?.id,
                tenantId = tenantId,
                provider = key,
                clientId = clientId.trim(),
                clientSecret = clientSecret.trimToNull() ?: existing?.clientSecret ?: "",
                enabled = enabled,
                kind = kind,
                displayName = displayName.trimToNull(),
                issuer = issuer.trimToNull(),
                authorizationEndpoint = authorizationEndpoint.trimToNull(),
                tokenEndpoint = tokenEndpoint.trimToNull(),
                jwksUri = jwksUri.trimToNull(),
                scopes = scopes.trim().replace(WHITESPACE, " ").ifEmpty { DEFAULT_OIDC_SCOPES },
                jitEnabled = jitEnabled,
                jitAllowedDomains = normaliseDomains(jitAllowedDomains),
                createdAt = existing?.createdAt ?: Instant.now(),
                updatedAt = Instant.now(),
            )

        problemWith(draft)?.let { return validation(it) }

        val persisted = if (existing == null) repository.save(draft) else repository.update(draft)
        return AdminResult.Success(persisted)
    }

    fun delete(
        tenantId: TenantId,
        key: ProviderKey,
    ): AdminResult<Unit> {
        repository.findByTenantAndProvider(tenantId, key)
            ?: return notFound("No identity provider '${key.value}' in this workspace.")
        repository.delete(tenantId, key)
        return AdminResult.Success(Unit)
    }

    /** Success carries the row being updated, or null when this is a create. */
    private fun resolveTarget(
        tenantId: TenantId,
        key: ProviderKey,
        id: Int?,
    ): AdminResult<IdentityProvider?> {
        if (id == null) return AdminResult.Success(repository.findByTenantAndProvider(tenantId, key))
        val stored =
            repository.findById(tenantId, id)
                ?: return notFound("No identity provider with id $id in this workspace.")
        if (stored.provider != key) return validation(KEY_IMMUTABLE)
        return AdminResult.Success(stored)
    }

    /** The first problem with an already-normalised draft, or null if it is valid. */
    private fun problemWith(draft: IdentityProvider): String? =
        kindMismatch(draft.provider, draft.kind)
            ?: lengthProblem(draft)
            ?: urlProblem(draft.issuer, "issuer", MAX_ISSUER)
            ?: urlProblem(draft.authorizationEndpoint, "authorization endpoint", MAX_ENDPOINT)
            ?: urlProblem(draft.tokenEndpoint, "token endpoint", MAX_ENDPOINT)
            ?: urlProblem(draft.jwksUri, "JWKS URI", MAX_ENDPOINT)
            ?: oidcProblem(draft)
            ?: draft.jitAllowedDomains.firstOrNull { !it.isDomainLike() }?.let {
                "'$it' is not a valid domain — enter a bare domain such as example.com."
            }

    private fun lengthProblem(draft: IdentityProvider): String? =
        when {
            draft.clientId.isEmpty() -> "A client ID is required."
            draft.clientId.length > MAX_CLIENT_ID -> "The client ID must be $MAX_CLIENT_ID characters or fewer."
            draft.clientSecret.isEmpty() -> "A client secret is required."
            draft.clientSecret.length > MAX_CLIENT_SECRET ->
                "The client secret must be $MAX_CLIENT_SECRET characters or fewer."
            (draft.displayName?.length ?: 0) > MAX_DISPLAY_NAME ->
                "The display name must be $MAX_DISPLAY_NAME characters or fewer."
            draft.scopes.length > MAX_SCOPES -> "The scope list must be $MAX_SCOPES characters or fewer."
            else -> null
        }

    private fun oidcProblem(draft: IdentityProvider): String? =
        when {
            draft.kind != ProviderKind.OIDC -> null
            draft.issuer == null -> "An OIDC provider requires an issuer URL."
            "openid" !in draft.scopes.split(" ") -> "An OIDC provider must request the 'openid' scope."
            else -> null
        }

    /** Trim, lower-case, drop empties, de-duplicate — so no reader ever has to. */
    private fun normaliseDomains(domains: List<String>): List<String> =
        domains
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    // Validated in the A-label form the gate compares over, so an operator's unicode domain and
    // the punycode an assertion arrives as are held to one rule.
    private fun String.isDomainLike(): Boolean {
        if (length > MAX_DOMAIN) return false
        val ascii = runCatching { IDN.toASCII(this) }.getOrNull() ?: return false
        return ascii.length <= MAX_DOMAIN && DOMAIN.matches(ascii)
    }

    // 'google' and 'github' resolve to compiled-in OAuth2 adapters; every other key is brokered
    // over generic OIDC. Letting the two cross would persist a row no adapter can serve.
    private fun kindMismatch(
        key: ProviderKey,
        kind: ProviderKind,
    ): String? =
        when {
            key in ProviderKey.RESERVED && kind != ProviderKind.OAUTH2 ->
                "'${key.value}' is a built-in provider and must stay an OAuth2 provider."
            key !in ProviderKey.RESERVED && kind != ProviderKind.OIDC ->
                "'${key.value}' has no built-in adapter, so it must be configured as an OIDC provider."
            else -> null
        }

    private fun urlProblem(
        value: String?,
        field: String,
        maxLength: Int,
    ): String? {
        if (value == null) return null
        if (value.length > maxLength) return "The $field must be $maxLength characters or fewer."
        val uri = runCatching { URI(value) }.getOrNull() ?: return "The $field must be a valid absolute URL."
        if (uri.scheme?.lowercase() !in URL_SCHEMES) return OidcUrlPolicy.problemWith(value, field)
        if (uri.host.isNullOrBlank()) return "The $field must include a host."
        // https, or http on loopback only. The discovery adapter refuses the same URLs at fetch
        // time; this one exists so an operator hears it at the point of the mistake.
        return OidcUrlPolicy.problemWith(value, field)
    }

    private fun String?.trimToNull(): String? = this?.trim()?.ifEmpty { null }

    private fun notFound(message: String): AdminResult<Nothing> = AdminResult.Failure(AdminError.NotFound(message))

    private fun validation(message: String): AdminResult<Nothing> = AdminResult.Failure(AdminError.Validation(message))
}
