package com.kauth.adapter.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kauth.domain.model.AccessTokenClaims
import com.kauth.domain.model.Application
import com.kauth.domain.model.Tenant
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
 * id_token:
 *   - Issued only when "openid" is in the requested scopes.
 *   - Contains: sub, iss, aud, exp, iat, nonce, email, email_verified, name, preferred_username.
 *
 * Key loading is cached via a simple in-memory map (tenant_id → Algorithm).
 * The cache is invalidated on key rotation (restart required — acceptable for MVP;
 * Phase 3 will add a TTL-based cache with live refresh).
 */
class JwtTokenAdapter(
    private val baseUrl: String,
    private val tenantKeyRepository: TenantKeyRepository
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
        nonce: String?
    ): TokenResponse {
        val (algorithm, _) = getOrCreateAlgorithm(tenant.id)
        val issuer    = issuerFor(tenant)
        val audience  = client?.clientId ?: tenant.slug
        val subject   = user.id.toString()
        val expiryMs  = (client?.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds) * 1_000L
        val expiresAt = Date(System.currentTimeMillis() + expiryMs)

        val accessToken = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withClaim("tenant_id", tenant.id)
            .withClaim("username", user.username)
            .withClaim("email", user.email)
            .withClaim("email_verified", user.emailVerified)
            .withClaim("name", user.fullName)
            .withClaim("preferred_username", user.username)
            .withClaim("scope", scopes.joinToString(" "))
            .withIssuedAt(Date())
            .withExpiresAt(expiresAt)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)

        val idToken = if ("openid" in scopes) {
            JWT.create()
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
        } else null

        val refreshToken = generateRefreshToken()
        val refreshExpirySeconds = tenant.refreshTokenExpirySeconds

        return TokenResponse(
            access_token       = accessToken,
            token_type         = "Bearer",
            expires_in         = expiryMs / 1_000L,
            refresh_token      = refreshToken,
            refresh_expires_in = refreshExpirySeconds,
            id_token           = idToken,
            scope              = scopes.joinToString(" ")
        )
    }

    // -------------------------------------------------------------------------
    // TokenPort — client credentials (M2M)
    // -------------------------------------------------------------------------

    override fun issueClientCredentialsToken(
        tenant: Tenant,
        client: Application,
        scopes: List<String>
    ): String {
        val (algorithm, _) = getOrCreateAlgorithm(tenant.id)
        val issuer    = issuerFor(tenant)
        val expiryMs  = (client.tokenExpiryOverride?.toLong() ?: tenant.tokenExpirySeconds) * 1_000L

        return JWT.create()
            .withIssuer(issuer)
            .withAudience(client.clientId)
            .withSubject(client.clientId)         // sub = client_id for M2M
            .withClaim("tenant_id", tenant.id)
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
            // Decode header to get kid and tenant_id claim without full verification first
            val decoded = JWT.decode(token)
            val tenantId = decoded.getClaim("tenant_id").asInt() ?: return null
            val (algorithm, _) = getOrCreateAlgorithm(tenantId)

            val verifier = JWT.require(algorithm).build()
            val verified = verifier.verify(token)

            val scopeStr = verified.getClaim("scope").asString() ?: ""
            AccessTokenClaims(
                sub       = verified.subject ?: "",
                iss       = verified.issuer ?: "",
                aud       = verified.audience?.firstOrNull() ?: "",
                tenantId  = tenantId,
                username  = verified.getClaim("username").asString(),
                email     = verified.getClaim("email").asString(),
                scopes    = scopeStr.split(" ").filter { it.isNotBlank() },
                issuedAt  = verified.issuedAtAsInstant?.epochSecond ?: 0L,
                expiresAt = verified.expiresAtAsInstant?.epochSecond ?: 0L
            )
        } catch (e: Exception) {
            log.debug("Token decode failed: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // TokenPort — JWKS
    // -------------------------------------------------------------------------

    override fun getTenantJwks(tenantId: Int): List<Map<String, Any>> {
        return tenantKeyRepository.findEnabledKeys(tenantId).map { key ->
            val publicKey = KeyGenerator.decodePublicKey(key.publicKeyPem)
            buildJwk(key.keyId, publicKey)
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Loads or creates the RS256 algorithm for a tenant.
     * On first call, fetches the active key from DB (or generates one).
     * Result is cached in memory for the lifetime of this instance.
     */
    private fun getOrCreateAlgorithm(tenantId: Int): Pair<Algorithm, RSAPublicKey> {
        algorithmCache[tenantId]?.let { return it }

        val key = tenantKeyRepository.findActiveKey(tenantId)
            ?: run {
                log.warn("No active key found for tenant $tenantId — key generation should happen at startup via KeyProvisioningService")
                throw IllegalStateException("No signing key available for tenant $tenantId")
            }

        val publicKey  = KeyGenerator.decodePublicKey(key.publicKeyPem)
        val privateKey = KeyGenerator.decodePrivateKey(key.privateKeyPem)
        val algorithm  = Algorithm.RSA256(publicKey, privateKey)

        algorithmCache[tenantId] = algorithm to publicKey
        return algorithm to publicKey
    }

    /**
     * Builds a JWK (JSON Web Key) representation of an RSA public key.
     * Spec: https://www.rfc-editor.org/rfc/rfc7517
     */
    private fun buildJwk(keyId: String, publicKey: RSAPublicKey): Map<String, Any> {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapOf(
            "kty" to "RSA",
            "use" to "sig",
            "alg" to "RS256",
            "kid" to keyId,
            "n"   to encoder.encodeToString(publicKey.modulus.toByteArrayUnsigned()),
            "e"   to encoder.encodeToString(publicKey.publicExponent.toByteArrayUnsigned())
        )
    }

    private fun issuerFor(tenant: Tenant): String =
        tenant.issuerUrl ?: "$baseUrl/t/${tenant.slug}"

    private fun generateRefreshToken(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.security.SecureRandom().generateSeed(32))

    /**
     * Converts a [BigInteger] to a byte array without a leading sign byte.
     * Required for correct JWK "n" and "e" encoding.
     */
    private fun BigInteger.toByteArrayUnsigned(): ByteArray {
        val bytes = toByteArray()
        return if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    /**
     * Invalidates the algorithm cache for a tenant (call after key rotation).
     */
    fun invalidateCache(tenantId: Int) {
        algorithmCache.remove(tenantId)
    }
}
