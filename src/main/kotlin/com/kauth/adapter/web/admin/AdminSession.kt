package com.kauth.adapter.web.admin

import java.time.Instant

/**
 * Admin console session — stored as an HMAC-signed cookie.
 *
 * Authentication flow:
 *   1. GET /admin/login → PKCE redirect to /t/master/protocol/openid-connect/auth
 *   2. Standard auth flow (password + MFA if enrolled) completes
 *   3. GET /admin/callback → code exchange → AdminSession set
 */
data class AdminSession(
    val userId: Int = 0,
    val tenantId: Int = 0,
    val username: String,
    val accessToken: String = "",
    val idToken: String = "",
    val adminSessionId: Int? = null,
    val createdAt: Long = Instant.now().epochSecond,
)
