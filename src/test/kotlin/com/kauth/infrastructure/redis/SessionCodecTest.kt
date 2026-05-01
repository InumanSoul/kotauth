package com.kauth.infrastructure.redis

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionCodecTest {
    @Test
    fun `round-trip preserves all fields including nullable ones`() {
        val now = Instant.parse("2026-04-29T12:00:00Z")
        val original =
            Session(
                id = SessionId(42),
                tenantId = TenantId(7),
                userId = UserId(99),
                clientId = ApplicationId(3),
                accessTokenHash = "a".repeat(64),
                refreshTokenHash = "r".repeat(64),
                scopes = "openid profile email",
                ipAddress = "10.0.0.1",
                userAgent = "Mozilla/5.0 …",
                createdAt = now,
                expiresAt = now.plusSeconds(3600),
                refreshExpiresAt = now.plusSeconds(86400),
                lastActivityAt = now,
                revokedAt = null,
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip preserves a fully-populated revoked session`() {
        val now = Instant.parse("2026-04-29T12:00:00Z")
        val original =
            Session(
                id = SessionId(1),
                tenantId = TenantId(1),
                userId = UserId(1),
                clientId = ApplicationId(1),
                accessTokenHash = "hash",
                refreshTokenHash = null,
                scopes = "openid",
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(60),
                refreshExpiresAt = null,
                lastActivityAt = now,
                revokedAt = now.plusSeconds(30),
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(true, decoded.isRevoked)
    }

    @Test
    fun `round-trip preserves the impersonator parent session id`() {
        val now = Instant.parse("2026-04-29T12:00:00Z")
        val original =
            Session(
                id = SessionId(2),
                tenantId = TenantId(1),
                userId = UserId(99),
                clientId = null,
                accessTokenHash = "imp",
                refreshTokenHash = null,
                scopes = "openid",
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(60),
                refreshExpiresAt = null,
                lastActivityAt = now,
                revokedAt = null,
                impersonatorSessionId = SessionId(1),
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(true, decoded.isImpersonation)
    }

    @Test
    fun `round-trip handles a client_credentials session (null userId)`() {
        val now = Instant.parse("2026-04-29T12:00:00Z")
        val original =
            Session(
                id = SessionId(1),
                tenantId = TenantId(1),
                userId = null,
                clientId = ApplicationId(5),
                accessTokenHash = "hash",
                refreshTokenHash = null,
                scopes = "api:read",
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(60),
                refreshExpiresAt = null,
                lastActivityAt = now,
                revokedAt = null,
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(original))

        assertEquals(original, decoded)
    }
}
