package com.kauth.adapter.web.portal

import java.time.Instant

/**
 * Self-service portal session — stored as an encrypted cookie per browser session.
 *
 * DESIGN NOTE (Phase 3b):
 * The portal uses a simple cookie session rather than requiring the user to go through
 * the full OAuth Authorization Code flow to access their own profile page. This is a
 * deliberate KISS decision: the portal IS part of the auth platform, not an external app,
 * so the circular dependency of requiring OAuth to access account management is avoided.
 *
 * The session is encrypted by Ktor's built-in session cookie encryption (configured in
 * Application.kt via SessionTransportTransformerMessageAuthentication).
 *
 * FUTURE (Phase 5): Replace with an OAuth-based portal when the SDK/developer experience
 * phase introduces first-class portal clients and account management APIs.
 *
 * Session TTL: 2 hours (configured in Application.kt cookie.maxAgeInSeconds).
 * The cookie is HttpOnly and tenant-scoped (cookie name includes slug).
 */
data class PortalSession(
    val userId   : Int,
    val tenantId : Int,
    val tenantSlug: String,
    val username : String,
    val createdAt: Long = Instant.now().epochSecond
)
