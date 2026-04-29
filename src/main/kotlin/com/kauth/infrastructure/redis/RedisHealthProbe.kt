package com.kauth.infrastructure.redis

import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object RedisHealthProbe {
    fun probe(
        connection: StatefulRedisConnection<String, String>,
        timeoutMs: Long,
    ): Result<Unit> {
        val future = connection.async().ping().toCompletableFuture()
        return try {
            val reply = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (reply.equals("PONG", ignoreCase = true)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Redis PING returned unexpected reply: $reply"))
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            Result.failure(IllegalStateException("Redis PING timed out after ${timeoutMs}ms", e))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Redis PING failed: ${e.message}", e))
        }
    }
}
