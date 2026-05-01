package com.kauth.infrastructure.redis

import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId

/**
 * Centralized Redis key shapes for the Kotauth keyspace.
 *
 * Layout for sessions:
 *
 *   kauth:session:rec:{id}                        STRING (JSON-encoded Session) — primary record
 *   kauth:session:tok:{accessTokenHash}           STRING (session id)           — access-token index
 *   kauth:session:rtok:{refreshTokenHash}         STRING (session id)           — refresh-token index
 *   kauth:session:active:user:{tenant}:{user}     ZSET   (member=id score=createdAtEpochMs)
 *   kauth:session:active:tenant:{tenant}          ZSET   (member=id score=createdAtEpochMs)
 *   kauth:session:next-id                         STRING (counter, INCR for new ids)
 */
internal object RedisKeys {
    const val PREFIX = "kauth:session"

    fun record(id: SessionId) = "$PREFIX:rec:${id.value}"

    fun accessTokenIndex(hash: String) = "$PREFIX:tok:$hash"

    fun refreshTokenIndex(hash: String) = "$PREFIX:rtok:$hash"

    fun activeUserSet(
        tenantId: TenantId,
        userId: UserId,
    ) = "$PREFIX:active:user:${tenantId.value}:${userId.value}"

    fun activeTenantSet(tenantId: TenantId) = "$PREFIX:active:tenant:${tenantId.value}"

    fun impersonatorSet(parentId: SessionId) = "$PREFIX:impersonator:${parentId.value}"

    const val ID_COUNTER = "$PREFIX:next-id"
}
