package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.ClaimTokenType
import com.kauth.domain.model.GrantType
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.Session
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantClaimMapper
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuthorizationCodeRepository
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.ResourceServerRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserAttributeRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.util.SecureTokens
import com.kauth.domain.util.sha256Hex
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * OAuth 2.0 / OIDC use cases — tenant-scoped.
 *
 * Implements the four core flows:
 *   1. Authorization Code Flow + PKCE
 *   2. Client Credentials Flow (M2M)
 *   3. Refresh Token Flow with rotation
 *   4. Token Introspection
 *
 * Integrates with:
 *   - [SessionRepository] for persisted token state (revocation, refresh rotation)
 *   - [AuthorizationCodeRepository] for single-use auth codes
 *   - [TokenPort] for cryptographic operations (RS256, JWKS)
 *   - [AuditLogPort] for append-only security event trail
 *
 * Does NOT handle login UI or HTTP concerns — those live in the route adapters.
 */
class OAuthService(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val applicationRepository: ApplicationRepository,
    private val sessionRepository: SessionRepository,
    private val authCodeRepository: AuthorizationCodeRepository,
    private val tokenPort: TokenPort,
    private val passwordHasher: PasswordHasher,
    private val auditLog: AuditLogPort,
    private val resourceServerRepository: ResourceServerRepository? = null,
    private val roleRepository: RoleRepository? = null,
    private val userAttributeRepository: UserAttributeRepository? = null,
    /**
     * Reader for tenant claim mappers. A lambda (instead of a port interface)
     * keeps the domain contract trivial while letting the composition root
     * inject the cached [com.kauth.infrastructure.CachingClaimMapperService].
     * Default returns empty — preserves pre-feature token shape.
     */
    private val claimMappersFor: (TenantId) -> List<TenantClaimMapper> = { _ -> emptyList() },
) {
    /**
     * Resolves the custom claim maps (access + id) for a user's issued token.
     * Returns (emptyMap, emptyMap) when either dependency is not wired —
     * the default adopted by existing tests via the nullable constructor params.
     */
    private fun buildCustomClaims(
        userId: UserId,
        tenantId: TenantId,
    ): Pair<Map<String, String>, Map<String, String>> {
        val repo = userAttributeRepository ?: return emptyMap<String, String>() to emptyMap()
        val mappers = claimMappersFor(tenantId)
        if (mappers.isEmpty()) return emptyMap<String, String>() to emptyMap()
        val attributes = repo.findAll(userId, tenantId)
        if (attributes.isEmpty()) return emptyMap<String, String>() to emptyMap()
        val access = ClaimMapperService.projectClaims(mappers, attributes, ClaimTokenType.ACCESS)
        val id = ClaimMapperService.projectClaims(mappers, attributes, ClaimTokenType.ID)
        return access to id
    }
    // -------------------------------------------------------------------------
    // Authorization Code Flow — Step 1: validate request, issue code
    // -------------------------------------------------------------------------

    /**
     * Verifies a (clientId, redirectUri) pair without issuing a code.
     *
     * Open-redirect protection for GET /authorize when handling `prompt=none`:
     * before bouncing the user back to `redirect_uri?error=login_required`,
     * the route must confirm the URI actually belongs to a registered, enabled
     * client. Returns false when the tenant, client, or redirect_uri does not
     * match — the route should refuse the redirect in that case.
     */
    fun validateRedirectUri(
        tenantSlug: String,
        clientId: String,
        redirectUri: String,
    ): Boolean {
        val tenant = tenantRepository.findBySlug(tenantSlug) ?: return false
        val client = applicationRepository.findByClientId(tenant.id, clientId) ?: return false
        if (!client.enabled) return false
        return client.redirectUris.contains(redirectUri)
    }

    /**
     * Validates an authorization request and issues a short-lived code.
     * Called after the user has authenticated successfully.
     *
     * @param tenantSlug         Tenant for which the auth is happening.
     * @param userId             ID of the authenticated user.
     * @param clientId           OAuth2 client_id string.
     * @param redirectUri        Must exactly match one of the client's registered URIs.
     * @param scopes             Requested scopes (space-separated string).
     * @param codeChallenge      PKCE S256 challenge (required for public clients).
     * @param codeChallengeMethod "S256" or null.
     * @param nonce              Optional nonce for id_token replay prevention.
     * @param state              Client state parameter — echoed back in redirect.
     * @param ipAddress          Caller IP for audit log.
     */
    fun issueAuthorizationCode(
        tenantSlug: String,
        userId: UserId,
        clientId: String,
        redirectUri: String,
        scopes: String,
        codeChallenge: String?,
        codeChallengeMethod: String?,
        nonce: String?,
        state: String?,
        ipAddress: String? = null,
        authTime: Instant = Instant.now(),
        resources: List<String> = emptyList(),
    ): OAuthResult<AuthorizationCode> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OAuthResult.Failure(OAuthError.TenantNotFound)

        val client =
            applicationRepository.findByClientId(tenant.id, clientId)
                ?: return OAuthResult.Failure(OAuthError.InvalidClient("Unknown client_id: $clientId"))

        if (!client.enabled) {
            return OAuthResult.Failure(OAuthError.InvalidClient("Client is disabled"))
        }

        if (GrantType.AUTHORIZATION_CODE !in client.grantTypes) {
            return OAuthResult.Failure(
                OAuthError.UnauthorizedClient("Client is not registered for the authorization_code grant"),
            )
        }

        // Validate redirect URI — exact match required (RFC 6749 §3.1.2.3)
        if (!client.redirectUris.contains(redirectUri)) {
            return OAuthResult.Failure(OAuthError.InvalidRedirectUri(redirectUri))
        }

        // PKCE required for public clients (Decision 3: Option A)
        if (client.accessType == AccessType.PUBLIC) {
            if (codeChallenge.isNullOrBlank()) {
                return OAuthResult.Failure(OAuthError.PkceRequired)
            }
            if (codeChallengeMethod != null && codeChallengeMethod != "S256") {
                return OAuthResult.Failure(OAuthError.InvalidRequest("Only S256 code_challenge_method is supported"))
            }
        }

        val code =
            AuthorizationCode(
                code = generateSecureCode(),
                tenantId = tenant.id,
                clientId = client.id,
                userId = userId,
                redirectUri = redirectUri,
                scopes = scopes.ifBlank { "openid" },
                codeChallenge = codeChallenge,
                codeChallengeMethod = if (codeChallenge != null) "S256" else null,
                nonce = nonce,
                state = state,
                expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_SECONDS),
                authTime = authTime,
                resources = resources,
            )

        val saved = authCodeRepository.save(code)

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = userId,
                clientId = client.id,
                eventType = AuditEventType.AUTHORIZATION_CODE_ISSUED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("scopes" to scopes, "redirect_uri" to redirectUri),
            ),
        )

        return OAuthResult.Success(saved)
    }

    // -------------------------------------------------------------------------
    // Authorization Code Flow — Step 2: exchange code for tokens
    // -------------------------------------------------------------------------

    /**
     * Exchanges a valid authorization code for a token set.
     * Validates PKCE, redirect URI, client authentication, and code state.
     */
    fun exchangeAuthorizationCode(
        tenantSlug: String,
        code: String,
        clientId: String,
        redirectUri: String,
        codeVerifier: String?,
        clientSecret: String?,
        ipAddress: String? = null,
        userAgent: String? = null,
        requestedResources: List<String> = emptyList(),
    ): OAuthResult<TokenResponse> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OAuthResult.Failure(OAuthError.TenantNotFound)

        val client =
            applicationRepository.findByClientId(tenant.id, clientId)
                ?: return OAuthResult.Failure(OAuthError.InvalidClient("Unknown client"))

        // Authenticate confidential clients
        if (client.accessType == AccessType.CONFIDENTIAL) {
            if (clientSecret == null) {
                return OAuthResult.Failure(OAuthError.InvalidClient("client_secret required for confidential clients"))
            }
            val storedHash = applicationRepository.findClientSecretHash(client.id)
            if (storedHash == null || !passwordHasher.verify(clientSecret, storedHash)) {
                return OAuthResult.Failure(OAuthError.InvalidClient("Invalid client_secret"))
            }
        }

        val authCode =
            authCodeRepository.findByCode(code)
                ?: return OAuthResult.Failure(OAuthError.InvalidGrant("Authorization code not found"))

        // Single-use enforcement
        if (!authCode.isValid) {
            if (authCode.isUsed) {
                // Potential replay attack — revoke all sessions for this user/client
                sessionRepository.revokeAllForUser(authCode.tenantId, authCode.userId)
                auditLog.record(
                    AuditEvent(
                        tenantId = tenant.id,
                        userId = authCode.userId,
                        clientId = client.id,
                        eventType = AuditEventType.SESSION_REVOKED,
                        ipAddress = ipAddress,
                        userAgent = userAgent,
                        details = mapOf("reason" to "authorization_code_replay_detected"),
                    ),
                )
            }
            return OAuthResult.Failure(OAuthError.InvalidGrant("Authorization code is expired or already used"))
        }

        // Validate redirect URI matches exactly what was used to obtain the code
        if (authCode.redirectUri != redirectUri) {
            return OAuthResult.Failure(OAuthError.InvalidGrant("redirect_uri mismatch"))
        }

        // PKCE verification
        if (authCode.codeChallenge != null) {
            if (codeVerifier == null) {
                return OAuthResult.Failure(OAuthError.InvalidGrant("code_verifier required"))
            }
            if (!verifyPkce(codeVerifier, authCode.codeChallenge)) {
                return OAuthResult.Failure(OAuthError.InvalidGrant("PKCE verification failed"))
            }
        }

        val user =
            userRepository.findById(authCode.userId, authCode.tenantId)
                ?: return OAuthResult.Failure(OAuthError.InvalidGrant("User not found"))

        if (!user.enabled) {
            return OAuthResult.Failure(OAuthError.InvalidGrant("User is disabled"))
        }

        val codeResources = authCode.resources
        val resolvedResources =
            when {
                requestedResources.isEmpty() -> codeResources
                requestedResources.toSet().all { it in codeResources.toSet() } -> requestedResources
                else -> return OAuthResult.Failure(OAuthError.InvalidTarget("requested resource not bound to code"))
            }

        if (resourceServerRepository == null && resolvedResources.isNotEmpty()) {
            return OAuthResult.Failure(OAuthError.InvalidTarget("resource indicators not configured"))
        }
        val resolvedResourceServers =
            if (resourceServerRepository != null) {
                resolvedResources.mapNotNull { resourceServerRepository.findByIdentifier(tenant.id, it) }
            } else {
                emptyList()
            }

        val requestedScopes = authCode.scopes.split(" ").filter { it.isNotBlank() }
        val finalScopes =
            when (val narrowing = narrowScopes(requestedScopes, resolvedResourceServers)) {
                is ScopeNarrowing.Ok -> narrowing.narrowed
                is ScopeNarrowing.InvalidScope -> return OAuthResult.Failure(
                    OAuthError.InvalidScope(narrowing.rejected),
                )
            }

        val effectiveRoles = roleRepository?.resolveEffectiveRoles(user.id!!, tenant.id) ?: emptyList()
        val (customAccessClaims, customIdClaims) = buildCustomClaims(user.id!!, tenant.id)

        val tokenResponse =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = client,
                scopes = finalScopes,
                nonce = authCode.nonce,
                roles = effectiveRoles,
                customAccessClaims = customAccessClaims,
                customIdClaims = customIdClaims,
                authTime = authCode.authTime,
                audiences = resolvedResources,
            )

        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = user.id,
                clientId = client.id,
                accessTokenHash = sha256Hex(tokenResponse.access_token),
                refreshTokenHash = tokenResponse.refresh_token?.let { sha256Hex(it) },
                scopes = finalScopes.joinToString(" "),
                ipAddress = ipAddress,
                userAgent = userAgent,
                expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                refreshExpiresAt =
                    tokenResponse.refresh_token?.let {
                        Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                    },
                resources = resolvedResources,
            ),
        )

        // Mark code as consumed
        authCodeRepository.markUsed(code)

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = client.id,
                eventType = AuditEventType.TOKEN_ISSUED,
                ipAddress = ipAddress,
                userAgent = userAgent,
                details = mapOf("grant_type" to "authorization_code", "scopes" to authCode.scopes),
            ),
        )

        return OAuthResult.Success(tokenResponse)
    }

    // -------------------------------------------------------------------------
    // Client Credentials Flow (M2M)
    // -------------------------------------------------------------------------

    /**
     * Authenticates a client directly (no user involved) and issues an access token.
     * Refresh tokens are NOT issued for M2M flows.
     */
    fun clientCredentials(
        tenantSlug: String,
        clientId: String,
        clientSecret: String,
        scopes: String,
        ipAddress: String? = null,
        resources: List<String> = emptyList(),
    ): OAuthResult<TokenResponse> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OAuthResult.Failure(OAuthError.TenantNotFound)

        val client =
            applicationRepository.findByClientId(tenant.id, clientId)
                ?: return OAuthResult.Failure(OAuthError.InvalidClient("Unknown client"))

        if (client.accessType != AccessType.CONFIDENTIAL) {
            return OAuthResult.Failure(
                OAuthError.InvalidClient(
                    "client_credentials flow requires a CONFIDENTIAL client",
                ),
            )
        }

        if (GrantType.CLIENT_CREDENTIALS !in client.grantTypes) {
            return OAuthResult.Failure(
                OAuthError.UnauthorizedClient("Client is not registered for the client_credentials grant"),
            )
        }

        if (!client.enabled) {
            return OAuthResult.Failure(OAuthError.InvalidClient("Client is disabled"))
        }

        val storedHash = applicationRepository.findClientSecretHash(client.id)
        if (storedHash == null || !passwordHasher.verify(clientSecret, storedHash)) {
            return OAuthResult.Failure(OAuthError.InvalidClient("Invalid client_secret"))
        }

        val audiences =
            when (val r = resolveAudiences(tenant.id, client, resources)) {
                is AudienceResolution.Ok -> r.values
                is AudienceResolution.Failed -> return OAuthResult.Failure(r.error)
            }

        val requestedScopes = scopes.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf("openid") }
        val finalScopes =
            if (resources.isNotEmpty()) {
                val resolvedServers =
                    if (resourceServerRepository != null) {
                        audiences.mapNotNull { resourceServerRepository.findByIdentifier(tenant.id, it) }
                    } else {
                        emptyList()
                    }
                when (val narrowing = narrowScopes(requestedScopes, resolvedServers)) {
                    is ScopeNarrowing.Ok -> narrowing.narrowed
                    is ScopeNarrowing.InvalidScope -> return OAuthResult.Failure(
                        OAuthError.InvalidScope(narrowing.rejected),
                    )
                }
            } else {
                requestedScopes
            }
        val accessToken = tokenPort.issueClientCredentialsToken(tenant, client, finalScopes, audiences)

        val expirySeconds = client.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds

        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = null,
                clientId = client.id,
                accessTokenHash = sha256Hex(accessToken),
                refreshTokenHash = null,
                scopes = finalScopes.joinToString(" "),
                ipAddress = ipAddress,
                userAgent = null,
                expiresAt = Instant.now().plusSeconds(expirySeconds),
                resources = audiences,
            ),
        )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = null,
                clientId = client.id,
                eventType = AuditEventType.TOKEN_ISSUED,
                ipAddress = ipAddress,
                userAgent = null,
                details =
                    mapOf(
                        "grant_type" to "client_credentials",
                        "resources" to audiences.joinToString(","),
                    ),
            ),
        )

        return OAuthResult.Success(
            TokenResponse(
                access_token = accessToken,
                token_type = "Bearer",
                expires_in = expirySeconds,
                scope = finalScopes.joinToString(" "),
            ),
        )
    }

    sealed class AudienceResolution {
        data class Ok(
            val values: List<String>,
        ) : AudienceResolution()

        data class Failed(
            val error: OAuthError.InvalidTarget,
        ) : AudienceResolution()
    }

    sealed class ScopeNarrowing {
        data class Ok(
            val narrowed: List<String>,
        ) : ScopeNarrowing()

        data class InvalidScope(
            val rejected: List<String>,
        ) : ScopeNarrowing()
    }

    fun narrowScopes(
        requested: List<String>,
        resolvedResources: List<ResourceServer>,
    ): ScopeNarrowing {
        if (requested.isEmpty()) return ScopeNarrowing.Ok(emptyList())

        val anyDeclares = resolvedResources.any { it.scopes.isNotEmpty() }
        if (!anyDeclares) return ScopeNarrowing.Ok(requested)

        val allowed = resolvedResources.flatMap { it.scopes }.toSet()
        val rejected = requested.filterNot { it in allowed }
        return if (rejected.isEmpty()) {
            ScopeNarrowing.Ok(requested)
        } else {
            ScopeNarrowing.InvalidScope(rejected)
        }
    }

    /** Callable from OAuthProtocolRoutes for authorize-time validation. */
    fun resolveAudiences(
        tenantId: TenantId,
        client: Application,
        requested: List<String>,
    ): AudienceResolution {
        if (requested.isEmpty()) {
            return AudienceResolution.Ok(listOf(client.audience ?: client.clientId))
        }
        if (resourceServerRepository == null) {
            return AudienceResolution.Failed(
                OAuthError.InvalidTarget("Resource indicators are not configured for this server."),
            )
        }
        val authorized = resourceServerRepository.listAuthorizedFor(client.id).map { it.identifier }.toSet()
        for (identifier in requested) {
            val rs =
                resourceServerRepository.findByIdentifier(tenantId, identifier)
                    ?: return AudienceResolution.Failed(
                        OAuthError.InvalidTarget("Unknown resource: $identifier"),
                    )
            if (!rs.enabled) {
                return AudienceResolution.Failed(
                    OAuthError.InvalidTarget("Resource is disabled: $identifier"),
                )
            }
            if (identifier !in authorized) {
                return AudienceResolution.Failed(
                    OAuthError.InvalidTarget("Client is not authorized for resource: $identifier"),
                )
            }
        }
        return AudienceResolution.Ok(requested)
    }

    fun resolveAudiencesForClient(
        tenantSlug: String,
        clientId: String,
        requested: List<String>,
    ): AudienceResolution {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return AudienceResolution.Failed(OAuthError.InvalidTarget("Unknown tenant: $tenantSlug"))
        val client =
            applicationRepository.findByClientId(tenant.id, clientId)
                ?: return AudienceResolution.Failed(OAuthError.InvalidTarget("Unknown client: $clientId"))
        return resolveAudiences(tenant.id, client, requested)
    }

    // -------------------------------------------------------------------------
    // Refresh Token Flow
    // -------------------------------------------------------------------------

    /**
     * Rotates a refresh token: validates the old token, issues new tokens,
     * revokes the old session (reason "rotated"), creates a new session.
     *
     * Implements refresh token rotation (OAuth 2.0 Security BCP).
     * Replay detection: presenting a refresh token that was already rotated
     * revokes all sessions for that user (token theft assumption). Tokens
     * invalidated by logout or admin revocation do NOT cascade — a benign
     * client retry after logout must not destroy unrelated sessions.
     */
    fun refreshTokens(
        tenantSlug: String,
        refreshToken: String,
        clientId: String,
        clientSecret: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
        requestedResources: List<String> = emptyList(),
    ): OAuthResult<TokenResponse> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OAuthResult.Failure(OAuthError.TenantNotFound)

        val hash = sha256Hex(refreshToken)
        val session = sessionRepository.findActiveByRefreshTokenHash(hash)

        if (session == null) {
            return handlePossibleRefreshReplay(tenant, hash, ipAddress, userAgent)
        }

        if (session.userId == null) {
            return OAuthResult.Failure(OAuthError.InvalidGrant("Refresh tokens not supported for M2M sessions"))
        }

        val user =
            userRepository.findById(session.userId, session.tenantId)
                ?: return OAuthResult.Failure(OAuthError.InvalidGrant("User no longer exists"))

        if (!user.enabled) {
            sessionRepository.revoke(session.id!!)
            return OAuthResult.Failure(OAuthError.InvalidGrant("User is disabled"))
        }

        val client = session.clientId?.let { applicationRepository.findById(it) }

        // RFC 6749 §10.4: verify the refresh token was issued to the requesting client
        if (client != null && client.clientId != clientId) {
            sessionRepository.revoke(session.id!!)
            return OAuthResult.Failure(OAuthError.InvalidGrant("Refresh token was not issued to this client"))
        }

        // RFC 6749 §6: confidential clients must authenticate on the refresh grant
        if (client != null && client.accessType == AccessType.CONFIDENTIAL) {
            if (clientSecret == null) {
                return OAuthResult.Failure(OAuthError.InvalidClient("client_secret required for confidential clients"))
            }
            val storedHash = applicationRepository.findClientSecretHash(client.id)
            if (storedHash == null || !passwordHasher.verify(clientSecret, storedHash)) {
                return OAuthResult.Failure(OAuthError.InvalidClient("Invalid client_secret"))
            }
        }

        if (client != null && GrantType.REFRESH_TOKEN !in client.grantTypes) {
            return OAuthResult.Failure(
                OAuthError.UnauthorizedClient("Client is not registered for the refresh_token grant"),
            )
        }

        // RFC 8707 §3: validate requested resources are a subset of the session's bound resources
        val sessionResources = session.resources
        val resolvedResources =
            when {
                requestedResources.isEmpty() -> sessionResources
                requestedResources.toSet().all { it in sessionResources.toSet() } -> requestedResources
                else -> return OAuthResult.Failure(OAuthError.InvalidTarget("requested resource not bound to session"))
            }

        if (resourceServerRepository == null && resolvedResources.isNotEmpty()) {
            return OAuthResult.Failure(OAuthError.InvalidTarget("resource indicators not configured"))
        }
        val resolvedResourceServers =
            if (resourceServerRepository != null) {
                resolvedResources.mapNotNull { resourceServerRepository.findByIdentifier(tenant.id, it) }
            } else {
                emptyList()
            }

        val resourcesNarrowed = requestedResources.isNotEmpty() && resolvedResources.toSet() != sessionResources.toSet()
        val sessionScopes = session.scopes.split(" ").filter { it.isNotBlank() }
        val effectiveScopes =
            if (resourcesNarrowed) {
                val anyDeclares = resolvedResourceServers.any { it.scopes.isNotEmpty() }
                if (anyDeclares) {
                    val allowed = resolvedResourceServers.flatMap { it.scopes }.toSet()
                    sessionScopes.filter { it in allowed }
                } else {
                    sessionScopes
                }
            } else {
                sessionScopes
            }
        val finalScopes =
            when (val narrowing = narrowScopes(effectiveScopes, resolvedResourceServers)) {
                is ScopeNarrowing.Ok -> narrowing.narrowed
                is ScopeNarrowing.InvalidScope -> return OAuthResult.Failure(
                    OAuthError.InvalidScope(narrowing.rejected),
                )
            }

        val effectiveRoles = roleRepository?.resolveEffectiveRoles(user.id!!, tenant.id) ?: emptyList()
        val (customAccessClaims, customIdClaims) = buildCustomClaims(user.id!!, tenant.id)

        val newTokens =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = client,
                scopes = finalScopes,
                roles = effectiveRoles,
                customAccessClaims = customAccessClaims,
                customIdClaims = customIdClaims,
                audiences = resolvedResources,
            )

        sessionRepository.revoke(session.id!!, reason = Session.REVOCATION_REASON_ROTATED)
        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = user.id,
                clientId = session.clientId,
                accessTokenHash = sha256Hex(newTokens.access_token),
                refreshTokenHash = newTokens.refresh_token?.let { sha256Hex(it) },
                scopes = finalScopes.joinToString(" "),
                ipAddress = ipAddress,
                userAgent = userAgent,
                expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                refreshExpiresAt =
                    newTokens.refresh_token?.let {
                        Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                    },
                resources = resolvedResources,
            ),
        )

        auditLog.record(
            AuditEvent(
                tenantId = tenant.id,
                userId = user.id,
                clientId = session.clientId,
                eventType = AuditEventType.TOKEN_REFRESHED,
                ipAddress = ipAddress,
                userAgent = userAgent,
            ),
        )

        return OAuthResult.Success(newTokens)
    }

    /**
     * Handles a refresh-token presentation that missed the active-session lookup.
     * If the hash matches a session revoked by rotation, this is a replayed
     * refresh token (OAuth 2.0 Security BCP §4.14.2: assume theft) — revoke
     * every session the user has and record an audit event.
     */
    private fun handlePossibleRefreshReplay(
        tenant: Tenant,
        hash: String,
        ipAddress: String?,
        userAgent: String?,
    ): OAuthResult<TokenResponse> {
        val stale = sessionRepository.findByRefreshTokenHash(hash)
        if (stale != null &&
            stale.tenantId == tenant.id &&
            stale.revocationReason == Session.REVOCATION_REASON_ROTATED &&
            stale.userId != null
        ) {
            sessionRepository.revokeAllForUser(stale.tenantId, stale.userId)
            auditLog.record(
                AuditEvent(
                    tenantId = stale.tenantId,
                    userId = stale.userId,
                    clientId = stale.clientId,
                    eventType = AuditEventType.SESSION_REVOKED,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    details = mapOf("reason" to "refresh_token_replay_detected"),
                ),
            )
        }
        return OAuthResult.Failure(OAuthError.InvalidGrant("Invalid or expired refresh token"))
    }

    // -------------------------------------------------------------------------
    // Token Introspection (RFC 7662)
    // -------------------------------------------------------------------------

    /**
     * Returns active status and claims for a token.
     *
     * RFC 7662 §2.1: the endpoint MUST authenticate the caller. Only
     * confidential clients of the tenant may introspect; invalid or missing
     * credentials return [IntrospectionResult.Unauthorized] so the route can
     * respond 401 instead of leaking an inactive/active distinction.
     */
    fun introspectToken(
        tenantSlug: String,
        token: String,
        clientId: String?,
        clientSecret: String?,
        tokenTypeHint: String? = null,
    ): IntrospectionResult {
        // Resolve tenant — unknown slugs always return inactive per RFC 7662
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return IntrospectionResult.Inactive

        if (authenticateConfidentialClient(tenant.id, clientId, clientSecret) == null) {
            return IntrospectionResult.Unauthorized
        }

        val hash = sha256Hex(token)

        // RFC 7662 §2: use tokenTypeHint to optimise lookup order
        val session =
            when (tokenTypeHint) {
                "refresh_token" ->
                    sessionRepository.findActiveByRefreshTokenHash(hash)
                        ?: sessionRepository.findActiveByAccessTokenHash(hash)
                else ->
                    sessionRepository.findActiveByAccessTokenHash(hash)
                        ?: sessionRepository.findActiveByRefreshTokenHash(hash)
            } ?: return IntrospectionResult.Inactive

        // Tenant isolation: token must belong to the requesting tenant
        if (session.tenantId != tenant.id) {
            return IntrospectionResult.Inactive
        }

        val claims =
            tokenPort.decodeAccessToken(token, tokenPort.issuerFor(tenant))
                ?: return IntrospectionResult.Inactive

        return IntrospectionResult.Active(
            sub = claims.sub,
            username = claims.username,
            email = claims.email,
            scopes = claims.scopes,
            expiresAt = claims.expiresAt,
            clientId =
                session.clientId?.let {
                    applicationRepository.findById(it)?.clientId
                },
            aud = claims.aud,
        )
    }

    // -------------------------------------------------------------------------
    // Token Revocation (RFC 7009)
    // -------------------------------------------------------------------------

    /**
     * Revokes an access or refresh token by hash lookup in the sessions table.
     *
     * RFC 7009 §2.1: the caller must authenticate. Returns false when client
     * authentication fails (route responds 401). After successful auth,
     * unknown tokens are a silent no-op per spec (don't leak token validity).
     * Only sessions belonging to the authenticated client's tenant are revocable.
     */
    fun revokeToken(
        tenantSlug: String,
        token: String,
        clientId: String?,
        clientSecret: String?,
    ): Boolean {
        val tenant = tenantRepository.findBySlug(tenantSlug) ?: return false
        authenticateConfidentialClient(tenant.id, clientId, clientSecret) ?: return false

        val hash = sha256Hex(token)
        val byAccess = sessionRepository.findActiveByAccessTokenHash(hash)
        val byRefresh = sessionRepository.findActiveByRefreshTokenHash(hash)
        val session = byAccess ?: byRefresh ?: return true // No-op per spec

        // Tenant isolation: an authenticated client may only revoke its tenant's tokens
        if (session.tenantId != tenant.id) return true

        session.id?.let {
            sessionRepository.revoke(it)
            auditLog.record(
                AuditEvent(
                    tenantId = session.tenantId,
                    userId = session.userId,
                    clientId = session.clientId,
                    eventType = AuditEventType.TOKEN_REVOKED,
                    ipAddress = null,
                    userAgent = null,
                ),
            )
        }
        return true
    }

    /**
     * Authenticates a confidential client by id + secret within a tenant.
     * Returns the client on success, null on any failure (unknown client,
     * public client, missing or invalid secret). Public clients are rejected:
     * introspection/revocation callers must be able to hold a secret.
     */
    private fun authenticateConfidentialClient(
        tenantId: TenantId,
        clientId: String?,
        clientSecret: String?,
    ): Application? {
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return null
        val client = applicationRepository.findByClientId(tenantId, clientId) ?: return null
        if (client.accessType != AccessType.CONFIDENTIAL || !client.enabled) return null
        val storedHash = applicationRepository.findClientSecretHash(client.id) ?: return null
        return if (passwordHasher.verify(clientSecret, storedHash)) client else null
    }

    // -------------------------------------------------------------------------
    // Userinfo (OIDC Core §5.3)
    // -------------------------------------------------------------------------

    /**
     * Returns user claims for a valid access token.
     * The bearer token is validated against the sessions table.
     */
    data class UserInfoResult(
        val sub: String,
        val username: String,
        val email: String,
        val emailVerified: Boolean,
        val name: String,
    )

    fun getUserInfo(accessToken: String): UserInfoResult? {
        val hash = sha256Hex(accessToken)
        val session = sessionRepository.findActiveByAccessTokenHash(hash) ?: return null
        val userId = session.userId ?: return null // No userinfo for M2M tokens

        val user = userRepository.findById(userId, session.tenantId) ?: return null
        if (!user.enabled) return null

        return UserInfoResult(
            sub = userId.value.toString(),
            username = user.username,
            email = user.email,
            emailVerified = user.emailVerified,
            name = user.fullName,
        )
    }

    // -------------------------------------------------------------------------
    // Logout / End Session
    // -------------------------------------------------------------------------

    /**
     * Revokes the session associated with the provided access token.
     * Optionally revokes all sessions for the user (global logout).
     */
    fun endSession(
        accessToken: String,
        revokeAll: Boolean = false,
        ipAddress: String? = null,
    ) {
        val hash = sha256Hex(accessToken)
        val session = sessionRepository.findActiveByAccessTokenHash(hash) ?: return

        if (revokeAll && session.userId != null) {
            sessionRepository.revokeAllForUser(session.tenantId, session.userId)
        } else {
            session.id?.let { sessionRepository.revoke(it) }
        }

        auditLog.record(
            AuditEvent(
                tenantId = session.tenantId,
                userId = session.userId,
                clientId = session.clientId,
                eventType = AuditEventType.SESSION_REVOKED,
                ipAddress = ipAddress,
                userAgent = null,
                details = mapOf("global_logout" to revokeAll.toString()),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // JWKS delegation (called from OIDC certs endpoint)
    // -------------------------------------------------------------------------

    fun getJwks(tenantId: TenantId): List<Map<String, Any>> = tokenPort.getTenantJwks(tenantId)

    // -------------------------------------------------------------------------
    // Internal utilities
    // -------------------------------------------------------------------------

    private fun generateSecureCode(): String = SecureTokens.randomBase64Url(32)

    private fun verifyPkce(
        codeVerifier: String,
        codeChallenge: String,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.US_ASCII),
            codeChallenge.toByteArray(Charsets.US_ASCII),
        )
    }

    companion object {
        private const val CODE_EXPIRY_SECONDS = 300L // 5 minutes
    }
}

// -------------------------------------------------------------------------
// Result types
// -------------------------------------------------------------------------

sealed class OAuthResult<out T> {
    data class Success<T>(
        val value: T,
    ) : OAuthResult<T>()

    data class Failure(
        val error: OAuthError,
    ) : OAuthResult<Nothing>()
}

sealed class OAuthError {
    object TenantNotFound : OAuthError()

    object PkceRequired : OAuthError()

    data class InvalidClient(
        val reason: String,
    ) : OAuthError()

    data class UnauthorizedClient(
        val reason: String,
    ) : OAuthError()

    data class InvalidGrant(
        val reason: String,
    ) : OAuthError()

    data class InvalidRequest(
        val reason: String,
    ) : OAuthError()

    data class InvalidRedirectUri(
        val uri: String,
    ) : OAuthError()

    object UnsupportedGrantType : OAuthError()

    data class InvalidTarget(
        val reason: String,
    ) : OAuthError()

    data class InvalidScope(
        val rejected: List<String>,
    ) : OAuthError()
}

sealed class IntrospectionResult {
    object Inactive : IntrospectionResult()

    /** Caller failed RFC 7662 §2.1 client authentication — route responds 401. */
    object Unauthorized : IntrospectionResult()

    data class Active(
        val sub: String,
        val username: String?,
        val email: String?,
        val scopes: List<String>,
        val expiresAt: Long,
        val clientId: String?,
        val aud: List<String>,
    ) : IntrospectionResult()
}
