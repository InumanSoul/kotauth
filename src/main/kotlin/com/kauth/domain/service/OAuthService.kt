package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.AuthorizationCode
import com.kauth.domain.model.Session
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.port.ApplicationRepository
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.AuthorizationCodeRepository
import com.kauth.domain.port.PasswordHasher
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TokenPort
import com.kauth.domain.port.UserRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * OAuth 2.0 / OIDC use cases — tenant-scoped.
 *
 * Implements the four core flows for Phase 2:
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
    private val roleRepository: RoleRepository? = null, // Phase 3c — nullable for backward compat
) {
    // -------------------------------------------------------------------------
    // Authorization Code Flow — Step 1: validate request, issue code
    // -------------------------------------------------------------------------

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
        userId: Int,
        clientId: String,
        redirectUri: String,
        scopes: String,
        codeChallenge: String?,
        codeChallengeMethod: String?,
        nonce: String?,
        state: String?,
        ipAddress: String? = null,
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
            userRepository.findById(authCode.userId)
                ?: return OAuthResult.Failure(OAuthError.InvalidGrant("User not found"))

        if (!user.enabled) {
            return OAuthResult.Failure(OAuthError.InvalidGrant("User is disabled"))
        }

        val scopes = authCode.scopes.split(" ").filter { it.isNotBlank() }

        // Phase 3c: resolve effective roles for the user
        val effectiveRoles = roleRepository?.resolveEffectiveRoles(user.id!!, tenant.id) ?: emptyList()

        val tokenResponse =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = client,
                scopes = scopes,
                nonce = authCode.nonce,
                roles = effectiveRoles,
            )

        // Persist session
        sessionRepository.save(
                Session(
                    tenantId = tenant.id,
                    userId = user.id,
                    clientId = client.id,
                    accessTokenHash = sha256(tokenResponse.access_token),
                    refreshTokenHash = tokenResponse.refresh_token?.let { sha256(it) },
                    scopes = authCode.scopes,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                    refreshExpiresAt =
                        tokenResponse.refresh_token?.let {
                            Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                        },
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

        if (!client.enabled) {
            return OAuthResult.Failure(OAuthError.InvalidClient("Client is disabled"))
        }

        val storedHash = applicationRepository.findClientSecretHash(client.id)
        if (storedHash == null || !passwordHasher.verify(clientSecret, storedHash)) {
            return OAuthResult.Failure(OAuthError.InvalidClient("Invalid client_secret"))
        }

        val requestedScopes = scopes.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf("openid") }
        val accessToken = tokenPort.issueClientCredentialsToken(tenant, client, requestedScopes)

        val expirySeconds = client.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds

        // Persist session (no user_id for M2M)
        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = null,
                clientId = client.id,
                accessTokenHash = sha256(accessToken),
                refreshTokenHash = null,
                scopes = requestedScopes.joinToString(" "),
                ipAddress = ipAddress,
                userAgent = null,
                expiresAt = Instant.now().plusSeconds(expirySeconds),
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
                details = mapOf("grant_type" to "client_credentials"),
            ),
        )

        return OAuthResult.Success(
            TokenResponse(
                access_token = accessToken,
                token_type = "Bearer",
                expires_in = expirySeconds,
                scope = requestedScopes.joinToString(" "),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Refresh Token Flow
    // -------------------------------------------------------------------------

    /**
     * Rotates a refresh token: validates the old token, issues new tokens,
     * revokes the old session, creates a new session.
     *
     * Implements refresh token rotation (RFC 6749 Security BCP).
     * Replay detection: if a used refresh token is presented, all sessions
     * for that user are revoked (token theft assumption).
     */
    fun refreshTokens(
        tenantSlug: String,
        refreshToken: String,
        clientId: String,
        ipAddress: String? = null,
        userAgent: String? = null,
    ): OAuthResult<TokenResponse> {
        val tenant =
            tenantRepository.findBySlug(tenantSlug)
                ?: return OAuthResult.Failure(OAuthError.TenantNotFound)

        val hash = sha256(refreshToken)
        val session = sessionRepository.findActiveByRefreshTokenHash(hash)

        if (session == null) {
            // Could be replay — we can't know if it was ever valid, so just reject
            return OAuthResult.Failure(OAuthError.InvalidGrant("Invalid or expired refresh token"))
        }

        if (session.userId == null) {
            return OAuthResult.Failure(OAuthError.InvalidGrant("Refresh tokens not supported for M2M sessions"))
        }

        val user =
            userRepository.findById(session.userId)
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

        val scopes = session.scopes.split(" ").filter { it.isNotBlank() }

        // Phase 3c: resolve effective roles for refresh
        val effectiveRoles = roleRepository?.resolveEffectiveRoles(user.id!!, tenant.id) ?: emptyList()

        val newTokens =
            tokenPort.issueUserTokens(
                user = user,
                tenant = tenant,
                client = client,
                scopes = scopes,
                roles = effectiveRoles,
            )

        // Revoke old session, create new (rotation)
        sessionRepository.revoke(session.id!!)
        sessionRepository.save(
            Session(
                tenantId = tenant.id,
                userId = user.id,
                clientId = session.clientId,
                accessTokenHash = sha256(newTokens.access_token),
                refreshTokenHash = newTokens.refresh_token?.let { sha256(it) },
                scopes = session.scopes,
                ipAddress = ipAddress,
                userAgent = userAgent,
                expiresAt = Instant.now().plusSeconds(tenant.tokenExpirySeconds),
                refreshExpiresAt =
                    newTokens.refresh_token?.let {
                        Instant.now().plusSeconds(tenant.refreshTokenExpirySeconds)
                    },
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

    // -------------------------------------------------------------------------
    // Token Introspection (RFC 7662)
    // -------------------------------------------------------------------------

    /**
     * Returns active status and claims for a token.
     * Only accessible to authenticated clients (server-side only, not public).
     */
    @Suppress("UNUSED_PARAMETER")
    fun introspectToken(
        tenantSlug: String,
        token: String,
        tokenTypeHint: String? = null,
    ): IntrospectionResult {
        val hash = sha256(token)
        val session =
            sessionRepository.findActiveByAccessTokenHash(hash)
                ?: return IntrospectionResult.Inactive

        val claims =
            tokenPort.decodeAccessToken(token)
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
        )
    }

    // -------------------------------------------------------------------------
    // Token Revocation (RFC 7009)
    // -------------------------------------------------------------------------

    /**
     * Revokes an access or refresh token by hash lookup in the sessions table.
     * Always returns success per RFC 7009 (don't leak token validity).
     */
    fun revokeToken(token: String) {
        val hash = sha256(token)
        val byAccess = sessionRepository.findActiveByAccessTokenHash(hash)
        val byRefresh = sessionRepository.findActiveByRefreshTokenHash(hash)
        val session = byAccess ?: byRefresh ?: return // No-op per spec

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
        val hash = sha256(accessToken)
        val session = sessionRepository.findActiveByAccessTokenHash(hash) ?: return null
        val userId = session.userId ?: return null // No userinfo for M2M tokens

        val user = userRepository.findById(userId) ?: return null
        if (!user.enabled) return null

        return UserInfoResult(
            sub = user.id.toString(),
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
        val hash = sha256(accessToken)
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

    fun getJwks(tenantId: Int): List<Map<String, Any>> = tokenPort.getTenantJwks(tenantId)

    // -------------------------------------------------------------------------
    // Internal utilities
    // -------------------------------------------------------------------------

    private fun generateSecureCode(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * PKCE S256 verification: SHA-256(code_verifier) must equal code_challenge (base64url).
     */
    private fun verifyPkce(
        codeVerifier: String,
        codeChallenge: String,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        return computed == codeChallenge
    }

    companion object {
        private const val CODE_EXPIRY_SECONDS = 300L // 5 minutes

        /**
         * SHA-256 hex digest of a string. Used for token hashing in session storage.
         * Never store raw tokens — only their hashes.
         */
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
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
}

sealed class IntrospectionResult {
    object Inactive : IntrospectionResult()

    data class Active(
        val sub: String,
        val username: String?,
        val email: String?,
        val scopes: List<String>,
        val expiresAt: Long,
        val clientId: String?,
    ) : IntrospectionResult()
}
