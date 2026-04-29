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

class RedisClientHolder internal constructor(
    private val client: RedisClient,
    val connection: StatefulRedisConnection<String, String>,
) {
    val commands: RedisCommands<String, String> = connection.sync()

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
        commandTimeoutMs: Long,
    ): RedisClientHolder {
        val redisUri = RedisURI.create(url)
        redisUri.timeout = Duration.ofMillis(commandTimeoutMs)
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
