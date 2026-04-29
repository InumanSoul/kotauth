package com.kauth.infrastructure.redis

import com.kauth.domain.port.RateLimiterPort
import io.lettuce.core.Range
import io.lettuce.core.RedisException
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID

class RedisRateLimiter(
    private val commands: RedisCommands<String, String>,
    override val maxRequests: Int,
    override val windowSeconds: Long,
    private val keyPrefix: String,
) : RateLimiterPort {
    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

    @Volatile private var scriptSha: String = commands.scriptLoad(SCRIPT)

    override fun isAllowed(key: String): Boolean {
        val redisKey = redisKey(key)
        val now = System.currentTimeMillis()
        val member = "$now:${UUID.randomUUID().toString().take(8)}"

        return try {
            val result = evalSlidingWindow(redisKey, now, member)
            result[0] == 1L
        } catch (e: RedisException) {
            log.warn("Redis unreachable, failing closed: {}", e.message)
            false
        }
    }

    override fun remaining(key: String): Int {
        val redisKey = redisKey(key)
        val now = System.currentTimeMillis()
        val windowStart = now - (windowSeconds * 1_000L)
        return try {
            commands.zremrangebyscore(redisKey, Range.create(0.0, windowStart.toDouble()))
            val card = commands.zcard(redisKey)
            maxOf(0, maxRequests - card.toInt())
        } catch (e: RedisException) {
            log.warn("Redis unreachable on remaining(): {}", e.message)
            0
        }
    }

    override fun reset(key: String) {
        val redisKey = redisKey(key)
        try {
            commands.del(redisKey)
        } catch (e: RedisException) {
            log.warn("Redis unreachable on reset(): {}", e.message)
        }
    }

    private fun redisKey(key: String) = "kauth:rl:$keyPrefix:$key"

    private fun evalSlidingWindow(
        redisKey: String,
        now: Long,
        member: String,
    ): List<Long> {
        val window = (windowSeconds * 1_000L).toString()
        val nowStr = now.toString()
        val maxStr = maxRequests.toString()
        return try {
            doEval(redisKey, nowStr, window, maxStr, member)
        } catch (e: RedisNoScriptException) {
            scriptSha = commands.scriptLoad(SCRIPT)
            doEval(redisKey, nowStr, window, maxStr, member)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun doEval(
        redisKey: String,
        now: String,
        window: String,
        max: String,
        member: String,
    ): List<Long> =
        commands.evalsha<List<Long>>(
            scriptSha,
            ScriptOutputType.MULTI,
            arrayOf(redisKey),
            now,
            window,
            max,
            member,
        )

    companion object {
        private val SCRIPT: String =
            RedisRateLimiter::class.java.classLoader
                .getResourceAsStream("redis/sliding_window.lua")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("redis/sliding_window.lua not found on classpath")
    }
}
