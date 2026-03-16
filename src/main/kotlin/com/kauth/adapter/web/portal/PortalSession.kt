package com.kauth.adapter.web.portal

import java.time.Instant

/**
 * Self-service portal session — stored as an HMAC-signed cookie per browser session.
 *
 * DESIGN NOTE (Phase 4):
 * The portal now authenticates through the standard OAuth 2.0 Authorization Code + PKCE
 * flow using the built-in 'kotauth-portal' PUBLIC client provisioned per tenant.
 *
 * Login flow:
 *   1. GET /t/{slug}/account/login → redirect to /t/{slug}/protocol/openid-connect/auth
 *      (standard auth endpoint — handles password, MFA, email-verification checks)
 *   2. User authenticates → auth endpoint redirects to /t/{slug}/account/callback?code=…
 *   3. Portal callback exchanges the code (PKCE verifier included) for tokens
 *   4. userId + username are decoded from the access token sub/preferred_username claims
 *   5. PortalSession is set and the user is redirected to /account/profile
 *
 * Benefits over the Phase 3b approach:
 *   - Single authentication path (no parallel login code duplication)
 *   - MFA, password-expiry, and all future auth checks happen in one place (AuthRoutes)
 *   - Portal session is backed by a real OAuth token issuance
 *   - The PKCE verifier is stored as a short-lived signed cookie, preventing CSRF
 *
 * The session is HMAC-signed by SessionTransportTransformerMessageAuthentication (Application.kt).
 * Session TTL: 4 hours (configured in Application.kt cookie.maxAgeInSeconds).
 * The cookie is HttpOnly.
 */
data class PortalSession(
    val userId    : Int,
    val tenantId  : Int,
    val tenantSlug: String,
    val username  : String,
    val createdAt : Long = Instant.now().epochSecond
)
