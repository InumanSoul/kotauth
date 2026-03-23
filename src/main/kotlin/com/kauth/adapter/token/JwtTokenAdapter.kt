package com.kauth.adapter.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TokenResponse
import com.kauth.domain.model.User
import com.kauth.domain.port.TenantKeyRepository
import com.kauth.domain.port.TokenPort
import com.kauth.infrastructure.KeyGenerator
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Token adapter — RS256 JWT implementation using per-tenant RSA key pairs.
 *
 * Responsibility: all cryptographic operations. No business logic, no HTTP,
 * no database writes (key reads only via [TenantKeyRepository]).
 *
 * RS256 (ADR-002):
 *   - Each tenant has its own RSA-2048 key pair stored in 'tenant_keys' (V5).
 *   - Access tokens and id_tokens are signed with the tenant's private key.
 *   - Clients verify tokens using the public key from the JWKS endpoint.
 *   - This enables offline token verification — no round-trip to KotAuth required.
 *
 * Role claims:
 *   - `realm_access` → { "roles": ["admin", "user"] } for tenant-scoped roles
 *   - `resource_access` → { "my-client": { "roles": ["editor"] } } for client-scoped roles
 *   These follow the Keycloak convention for maximum compatibility.
 *
 * Key loading is cached via a simple in-memory map (tenant_id → Algorithm).
 */
class JwtTokenAdapter(
    private val baseUrl: String,
    private val tenantKeyRepository: TenantKeyRepository,
) : TokenPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val algorithmCache = mutableMapOf<Int, Pair<Algorithm, RSAPublicKey>>()

    // -------------------------------------------------------------------------
    // TokenPort — issue user tokens
    // -------------------------------------------------------------------------

    override fun issueUserTokens(
        user: User,
        tenant: Tenant,
        client: Application?,
        scopes: List<String>,
        nonce: String?,
        roles: List<Role>,
    ): TokenResponse {
        val (algorithm, _) = getOrCreateAlgorithm(tenant.id.value)
        val issuer = issuerFor(tenant)
        val audience = client?.clientId ?: tenant.slug
        val subject = user.id!!.value.toString()
        val expiryMs = (client?.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds) * 1_000L
        val expiresAt = Date(System.currentTimeMillis() + expiryMs)

        val tenantRoles = roles.filter { it.scope == RoleScope.TENANT }.map { it.name }
        val clientRolesMap =
            roles
                .filter { it.scope == RoleScope.CLIENT }
                .groupBy { it.clientId }

        val accessTokenBuilder =
            JWT
                .create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withSubject(subject)
                .withClaim("tenant_id", tenant.id.value)
                .withClaim("username", user.username)
                .withClaim("email", user.email)
                .withClaim("email_verified", user.emailVerified)
                .withClaim("name", user.fullName)
                .withClaim("preferred_username", user.username)
                .withClaim("scope", scopes.joinToString(" "))
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .withJWTId(UUID.randomUUID().toString())

        // Embed realm_access (tenant-scoped roles)
        if (tenantRoles.isNotEmpty()) {
            accessTokenBuilder.withClaim("realm_access", mapOf("roles" to tenantRoles))
        }

        // Embed resource_access (client-scoped roles, keyed by the client's string clientId).
        // Roles carry the integer FK (clientId: Int?) from the DB. We resolve the human-readable
        // string identifier by matching against the Application passed to this call.
        // In Authorization Code Flow the user authenticates against exactly one client, so the
        // client param covers all CLIENT-scoped roles in the resolved list.
        if (clientRolesMap.isNotEmpty()) {
            val resourceAccess = mutableMapOf<String, Map<String, List<String>>>()
            for ((_, clientRoles) in clientRolesMap) {
                val appClientIdInt = clientRoles.firstOrNull()?.clientId ?: continue
                // Prefer the string clientId from the Application domain object.
                // Falls back to the integer-as-string only if the role belongs to a different
                // client than the one being issued tokens for (edge case in multi-app tenants).
                val stringClientId =
                    if (client != null && client.id == appClientIdInt) {
                        client.clientId
                    } else {
                        appClientIdInt.toString()
                    }
                val roleNames = clientRoles.map { it.name }
                resourceAccess[stringClientId] = mapOf("roles" to roleNames)
            }
            if (resourceAccess.isNotEmpty()) {
                accessTokenBuilder.withClaim("resource_access", resourceAccess as Map<String, *>)
            }
        }

        val accessToken = accessTokenBuilder.sign(algorithm)

        val idToken =
            if ("openid" in scopes) {
                JWT
                    .create()
                    .withIssuer(issuer)
                    .withAudience(audience)
                    .withSubject(subject)
                    .withClaim("email", user.email)
                    .withClaim("email_verified", user.emailVerified)
                    .withClaim("name", user.fullName)
                    .withClaim("preferred_username", user.username)
                    .apply { if (nonce != null) withClaim("nonce", nonce) }
                    .withIssuedAt(Date())
                    .withExpiresAt(expiresAt)
                    .sign(algorithm)
            } else {
                null
            }

        val refreshToken = generateRefreshToken()
        val refreshExpirySeconds = tenant.refreshTokenExpirySeconds

        return TokenResponse(
            access_token = accessToken,
            token_type = "Bearer",
            expires_in = expiryMs / 1_000L,
            refresh_token = refreshToken,
            refresh_expires_in = refreshExpirySeconds,
            id_token = idToken,
            scope = scopes.joinToString(" "),
        )
    }

    // -------------------------------------------------------------------------
    // TokenPort — client credentials (M2M)
    // -------------------------------------------------------------------------

    override fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>,
    ): String {
        val (algorithm, _) = getOrCreateAlgorithm(tenant.id.value)
        val issuer = issuerFor(tenant)
        val expiryMs = (client.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds) * 1_000L

        return JWT
            .create()
            .withIssuer(issuer)
            .withAudience(client.clientId)
            .withSubject(client.clientId) // sub = client_id for M2M
            .withClaim("tenant_id", tenant.id.value)
            .withClaim("client_id", client.clientId)
            .withClaim("scope", scopes.joinToString(" "))
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + expiryMs))
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)
    }

    // -------------------------------------------------------------------------
    // TokenPort — decode / verify
    // -------------------------------------------------------------------------

    override fun decodeAccessToken(token: String): AccessTokenClaims? {
        return try {
            val decoded = JWT.decode(token)
            val tenantIdRaw = decoded.getClaim("tenant_id").asInt() ?: return null
            val (algorithm, _) = getOrCreateAlgorithm(tenantIdRaw)

            val verifier = JWT.require(algorithm).build()
            val verified = verifier.verify(token)

            val scopeStr = verified.getClaim("scope").asString() ?: ""

            // Decode role claims
            val realmRoles =
                try {
                    val realmAccess = verified.getClaim("realm_access").asMap()
                    @Suppress("UNCHECKED_CAST")
                    (realmAccess?.get("roles") as? List<String>) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

            val resourceRoles =
                try {
                    val resourceAccess = verified.getClaim("resource_access").asMap()
                    resourceAccess?.mapValues { (_, v) ->
                        @Suppress("UNCHECKED_CAST")
                        val inner = v as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        (inner?.get("roles") as? List<String>) ?: emptyList()
                    } ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

            AccessTokenClaims(
                sub = verified.subject ?: "",
                iss = verified.issuer ?: "",
                aud = verified.audience?.firstOrNull() ?: "",
                tenantId = TenantId(tenantIdRaw),
                username = verified.getClaim("username").asString(),
                email = verified.getClaim("email").asString(),
                scopes = scopeStr.split(" ").filter { it.isNotBlank() },
                issuedAt = verified.issuedAtAsInstant?.epochSecond ?: 0L,
                expiresAt = verified.expiresAtAsInstant?.epochSecond ?: 0L,
                realmRoles = realmRoles,
                resourceRoles = resourceRoles,
            )
        } catch (e: Exception) {
            log.debug("Token decode failed: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // TokenPort — JWKS
    // -------------------------------------------------------------------------

    override fun getTenantJwks(tenantId: TenantId): List<Map<String, Any>> =
        tenantKeyRepository.findEnabledKeys(tenantId).map { key ->
            val publicKey = KeyGenerator.decodePublicKey(key.publicKeyPem)
            buildJwk(key.keyId, publicKey)
        }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun getOrCreateAlgorithm(tenantId: Int): Pair<Algorithm, RSAPublicKey> {
        algorithmCache[tenantId]?.let { return it }

        val key =
            tenantKeyRepository.findActiveKey(TenantId(tenantId))
                ?: run {
                    log.warn(
                        "No active key found for tenant $tenantId — key generation should happen at startup via KeyProvisioningService",
                    )
                    throw IllegalStateException("No signing key available for tenant $tenantId")
                }

        val publicKey = KeyGenerator.decodePublicKey(key.publicKeyPem)
        val privateKey = KeyGenerator.decodePrivateKey(key.privateKeyPem)
        val algorithm = Algorithm.RSA256(publicKey, privateKey)

        algorithmCache[tenantId] = algorithm to publicKey
        return algorithm to publicKey
    }

    private fun buildJwk(
        keyId: String,
        publicKey: RSAPublicKey,
    ): Map<String, Any> {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapOf(
            "kty" to "RSA",
            "use" to "sig",
            "alg" to "RS256",
            "kid" to keyId,
            "n" to encoder.encodeToString(publicKey.modulus.toByteArrayUnsigned()),
            "e" to encoder.encodeToString(publicKey.publicExponent.toByteArrayUnsigned()),
        )
    }

    private fun issuerFor(tenant: Tenant): String = tenant.issuerUrl ?: "$baseUrl/t/${tenant.slug}"

    private fun generateRefreshToken(): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(java.security.SecureRandom().generateSeed(32))

    private fun BigInteger.toByteArrayUnsigned(): ByteArray {
        val bytes = toByteArray()
        return if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    fun invalidateCache(tenantId: Int) {
        algorithmCache.remove(tenantId)
    }
}
