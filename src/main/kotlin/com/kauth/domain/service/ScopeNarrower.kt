package com.kauth.domain.service

import com.kauth.domain.model.OidcScopes
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId

object ScopeNarrower {
    /**
     * Narrows a scope request to what the token may actually carry.
     *
     * Three-way intersection: what was **requested**, what the targeted resource servers
     * **declare**, and what this **client** was granted on each of them. The OIDC identity scopes
     * ([OidcScopes.IDENTITY]) are about the user, not an API, and pass through unchanged.
     *
     * [clientAllowedScopes] is required rather than defaulted on purpose. Before v1.25.0 the
     * intersection stopped at what the resource server declared, so authorizing a client
     * against an API granted it every scope that API offered — separate credentials bought
     * independent revocation and audit attribution, but not least privilege. A default value
     * here would let a new call site silently reproduce that, so every caller must state what
     * the client is allowed. An absent entry means "nothing on that resource", never
     * "everything".
     */
    fun narrow(
        requested: List<String>,
        resolvedResources: List<ResourceServer>,
        clientAllowedScopes: Map<ResourceServerId, Set<String>>,
    ): OAuthService.ScopeNarrowing {
        if (requested.isEmpty()) return OAuthService.ScopeNarrowing.Ok(emptyList())

        // A resource server that declares no scopes narrows nothing — pre-existing behaviour
        // for deployments that predate the scopes column (v1.18.0). Unchanged here.
        val anyDeclares = resolvedResources.any { it.scopes.isNotEmpty() }
        if (!anyDeclares) return OAuthService.ScopeNarrowing.Ok(requested)

        val allowed =
            resolvedResources
                .flatMap { rs ->
                    val declared = rs.scopes.toSet()
                    val grantedToClient = rs.id?.let { clientAllowedScopes[it] } ?: emptySet()
                    declared intersect grantedToClient
                }.toSet()
        val rejected = requested.filterNot { it in allowed || it in OidcScopes.IDENTITY }
        return if (rejected.isEmpty()) {
            OAuthService.ScopeNarrowing.Ok(requested)
        } else {
            OAuthService.ScopeNarrowing.InvalidScope(rejected)
        }
    }
}
