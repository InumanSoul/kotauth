package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.DEFAULT_OIDC_SCOPES
import com.kauth.domain.model.IdentityProvider
import com.kauth.domain.model.ProviderKey
import com.kauth.domain.model.ProviderKind
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.IdentityProviderService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * REST API — per-tenant identity provider configuration.
 *
 * Mounted under `/t/{tenantSlug}/api/v1/identity-providers` inside the authenticated/
 * tenant-scoped route in [apiRoutes].
 *
 * Every write goes through [IdentityProviderService], the same validation point the admin
 * UI uses, so the issuer-required-for-OIDC rule, the https-only URL policy, the immutable
 * key and the domain normalisation hold identically on both surfaces.
 *
 * `client_secret` is write-only: accepted on [put], never returned by any read. See
 * [IdentityProviderDto] for how that is enforced.
 */
internal fun Route.apiIdentityProviderRoutes(identityProviderService: IdentityProviderService) {
    route("/identity-providers") {
        get {
            requireScope(call, ApiScope.IDENTITY_PROVIDERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val providers = identityProviderService.list(tenantId).map { publish(it) }
            call.respond(HttpStatusCode.OK, IdentityProvidersDto(providers))
        }

        get("/{providerKey}") {
            requireScope(call, ApiScope.IDENTITY_PROVIDERS_READ) ?: return@get
            val tenantId = call.attributes[TenantIdAttr]
            val key = call.parseProviderKeyOr { return@get } ?: return@get
            val provider =
                identityProviderService.get(tenantId, key)
                    ?: return@get call.respondProblem(
                        HttpStatusCode.NotFound,
                        "Not Found",
                        "No identity provider '${key.value}' in this workspace.",
                    )
            call.respond(HttpStatusCode.OK, publish(provider))
        }

        put("/{providerKey}") {
            requireScope(call, ApiScope.IDENTITY_PROVIDERS_WRITE) ?: return@put
            val tenantId = call.attributes[TenantIdAttr]
            val key = call.parseProviderKeyOr { return@put } ?: return@put
            val body = call.receive<UpsertIdentityProviderRequest>()
            val requestedKind = body.kind?.trim()?.lowercase()
            val kind =
                if (requestedKind == null) {
                    defaultKindFor(key)
                } else {
                    ProviderKind.of(requestedKind)
                        ?: return@put call.respondProblem(
                            HttpStatusCode.UnprocessableEntity,
                            "Validation Error",
                            "'${body.kind}' is not a provider kind — use 'oauth2' or 'oidc'.",
                        )
                }

            // The row is read first for two reasons: PUT reports 201 only when it created
            // something, and the JIT fields this surface does not expose must survive a write
            // that says nothing about them.
            val existing = identityProviderService.get(tenantId, key)

            val result =
                identityProviderService.save(
                    tenantId = tenantId,
                    key = key,
                    clientId = body.clientId,
                    clientSecret = body.clientSecret,
                    kind = kind,
                    enabled = body.enabled,
                    displayName = body.displayName,
                    issuer = body.issuer,
                    authorizationEndpoint = body.authorizationEndpoint,
                    tokenEndpoint = body.tokenEndpoint,
                    jwksUri = body.jwksUri,
                    scopes = body.scopes ?: existing?.scopes ?: DEFAULT_OIDC_SCOPES,
                    jitEnabled = existing?.jitEnabled ?: false,
                    jitAllowedDomains = existing?.jitAllowedDomains ?: emptyList(),
                )

            when (result) {
                is AdminResult.Success ->
                    call.respond(
                        if (existing == null) HttpStatusCode.Created else HttpStatusCode.OK,
                        publish(result.value),
                    )
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }

        delete("/{providerKey}") {
            requireScope(call, ApiScope.IDENTITY_PROVIDERS_WRITE) ?: return@delete
            val tenantId = call.attributes[TenantIdAttr]
            val key = call.parseProviderKeyOr { return@delete } ?: return@delete
            when (val result = identityProviderService.delete(tenantId, key)) {
                is AdminResult.Success -> call.respond(HttpStatusCode.NoContent, "")
                is AdminResult.Failure -> call.respondAdminError(result.error)
            }
        }
    }
}

/** Parses `{providerKey}`, or replies 400 and invokes [bail] if it is missing or malformed. */
private suspend inline fun ApplicationCall.parseProviderKeyOr(bail: () -> Nothing): ProviderKey? {
    val key = parameters["providerKey"]?.let { ProviderKey.of(it) }
    return if (key == null) {
        respondProblem(
            HttpStatusCode.BadRequest,
            "Invalid provider key",
            "A provider key is 1–32 characters of a–z, 0–9 and '-'.",
        )
        bail()
    } else {
        key
    }
}

/**
 * The kind a key must be when the caller does not say: the two reserved keys reach a
 * compiled-in OAuth2 adapter, every other key is brokered over OIDC. The service refuses any
 * other pairing, so defaulting here only saves the caller from restating what the key implies.
 */
private fun defaultKindFor(key: ProviderKey): ProviderKind =
    if (key in ProviderKey.RESERVED) ProviderKind.OAUTH2 else ProviderKind.OIDC

/**
 * The only place a stored row becomes a response. It reads the fields it is allowed to
 * publish one by one; the row's decrypted secret is simply never one of them.
 */
private fun publish(provider: IdentityProvider): IdentityProviderDto =
    IdentityProviderDto(
        key = provider.provider.value,
        kind = provider.kind.value,
        clientId = provider.clientId,
        enabled = provider.enabled,
        displayName = provider.displayName,
        issuer = provider.issuer,
        authorizationEndpoint = provider.authorizationEndpoint,
        tokenEndpoint = provider.tokenEndpoint,
        jwksUri = provider.jwksUri,
        scopes = provider.scopes,
        createdAt = isoFormatter.format(provider.createdAt),
        updatedAt = isoFormatter.format(provider.updatedAt),
    )

/**
 * Request body for `PUT /identity-providers/{key}` — create or update.
 *
 * `kind` defaults to what the key implies. A null or blank `clientSecret` keeps the stored
 * secret on update and is rejected on create — there is no way to read a secret back, so a
 * caller that only wants to flip `enabled` must be able to omit it.
 */
@Serializable
data class UpsertIdentityProviderRequest(
    val clientId: String,
    val clientSecret: String? = null,
    val kind: String? = null,
    val enabled: Boolean = true,
    val displayName: String? = null,
    val issuer: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val jwksUri: String? = null,
    val scopes: String? = null,
)

/**
 * The wire shape of a configured provider.
 *
 * This is not [IdentityProvider] and not a redaction of it: the type has no field capable of
 * holding a client secret, so no serialiser setting, no added mapping line and no `copy()`
 * can put one on the wire. It is deliberately not a `data class` for the same reason — there
 * is no `copy()` to carry an extra field through. `ApiIdentityProviderRoutesTest` pins the
 * serial descriptor's element names, so adding a field here fails the build rather than the
 * next security review.
 */
@Serializable
class IdentityProviderDto(
    val key: String,
    val kind: String,
    val clientId: String,
    val enabled: Boolean,
    val displayName: String?,
    val issuer: String?,
    val authorizationEndpoint: String?,
    val tokenEndpoint: String?,
    val jwksUri: String?,
    val scopes: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
class IdentityProvidersDto(
    val providers: List<IdentityProviderDto>,
)
