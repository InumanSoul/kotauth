package com.kauth.infrastructure.redis

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * JSON codec for [Session] records persisted to Redis.
 *
 * The DTO is private to this file so the domain [Session] stays free of any
 * serialization annotations — kotlinx.serialization is treated as an
 * infrastructure concern, not a domain one.
 */
internal object SessionCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(session: Session): String = json.encodeToString(SessionDto.serializer(), session.toDto())

    fun decode(payload: String): Session = json.decodeFromString(SessionDto.serializer(), payload).toSession()

    @Serializable
    private data class SessionDto(
        val id: Int?,
        val tenantId: Int,
        val userId: Int?,
        val clientId: Int?,
        val accessTokenHash: String,
        val refreshTokenHash: String?,
        val scopes: String,
        val ipAddress: String?,
        val userAgent: String?,
        val createdAt: String,
        val expiresAt: String,
        val refreshExpiresAt: String?,
        val lastActivityAt: String,
        val revokedAt: String?,
        val impersonatorSessionId: Int? = null,
    )

    private fun Session.toDto() =
        SessionDto(
            id = id?.value,
            tenantId = tenantId.value,
            userId = userId?.value,
            clientId = clientId?.value,
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            scopes = scopes,
            ipAddress = ipAddress,
            userAgent = userAgent,
            createdAt = createdAt.toString(),
            expiresAt = expiresAt.toString(),
            refreshExpiresAt = refreshExpiresAt?.toString(),
            lastActivityAt = lastActivityAt.toString(),
            revokedAt = revokedAt?.toString(),
            impersonatorSessionId = impersonatorSessionId?.value,
        )

    private fun SessionDto.toSession() =
        Session(
            id = id?.let { SessionId(it) },
            tenantId = TenantId(tenantId),
            userId = userId?.let { UserId(it) },
            clientId = clientId?.let { ApplicationId(it) },
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            scopes = scopes,
            ipAddress = ipAddress,
            userAgent = userAgent,
            createdAt = Instant.parse(createdAt),
            expiresAt = Instant.parse(expiresAt),
            refreshExpiresAt = refreshExpiresAt?.let(Instant::parse),
            lastActivityAt = Instant.parse(lastActivityAt),
            revokedAt = revokedAt?.let(Instant::parse),
            impersonatorSessionId = impersonatorSessionId?.let { SessionId(it) },
        )
}
