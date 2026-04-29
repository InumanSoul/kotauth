package com.kauth.infrastructure.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.ClientOptions.DisconnectedBehavior
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCredentials
import io.lettuce.core.RedisURI
import io.lettuce.core.StaticCredentialsProvider
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RedisClientHolder internal constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String>,
) {
    val commands: RedisCommands<String, String> = connection.sync()

    /**
     * Sends `PING` with an explicit timeout independent of the per-command
     * timeout. Used by the startup probe so a hanging connection at boot can't
     * stall startup beyond [timeoutMs]. Returns failure when the reply is not
     * `PONG`, when the timeout fires, or when the underlying call throws.
     */
    fun ping(timeoutMs: Long): Result<Unit> {
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

    fun shutdown() {
        connection.close()
        client.shutdown()
    }
}

object RedisClientFactory {
    fun create(
        url: String,
        username: String?,
        password: String?,
        connectTimeoutMs: Long,
        commandTimeoutMs: Long,
    ): RedisClientHolder {
        val redisUri = RedisURI.create(url)
        redisUri.timeout = Duration.ofMillis(connectTimeoutMs)
        val creds =
            when {
                username != null -> RedisCredentials.just(username, password ?: "")
                password != null -> RedisCredentials.just("default", password)
                else -> null
            }
        if (creds != null) {
            redisUri.credentialsProvider = StaticCredentialsProvider(creds)
        }

        val client =
            RedisClient.create(redisUri).apply {
                options =
                    ClientOptions
                        .builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
                        .build()
            }

        val connection = client.connect()
        connection.setTimeout(Duration.ofMillis(commandTimeoutMs))

        return RedisClientHolder(client, connection)
    }
}
