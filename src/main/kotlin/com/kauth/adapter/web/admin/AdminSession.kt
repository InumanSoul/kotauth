package com.kauth.adapter.web.admin

import java.time.Instant

/**
 * Admin console session — stored as an HMAC-signed cookie.
 *
 * Authentication flow (OAuth mode — default):
 *   1. GET /admin/login → PKCE redirect to /t/master/protocol/openid-connect/auth
 *   2. Standard auth flow (password + MFA if enrolled) completes
 *   3. GET /admin/callback → code exchange → AdminSession set
 *
 * The session is backed by a real entry in the sessions table ([adminSessionId]),
 * so it appears in the sessions UI and can be revoked like any other session.
 *
 * Break-glass (KAUTH_ADMIN_BYPASS=true):
 *   Direct username/password login sets AdminSession with userId/tenantId from
 *   the authenticated user but no adminSessionId (session not tracked in DB).
 */
data class AdminSession(
    val userId: Int = 0,
    val tenantId: Int = 0,
    val username: String,
    val accessToken: String = "",
    val adminSessionId: Int? = null,
    val createdAt: Long = Instant.now().epochSecond,
)
