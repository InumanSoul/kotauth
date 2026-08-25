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
    // Every limiter on the auth path (and writes) must fail closed — this default preserves
    // that for every existing call site. Only the read limiter opts in to failing open; see the
    // comment where it's constructed in ServiceGraph for why that asymmetry is intentional.
    private val failOpen: Boolean = false,
) : RateLimiterPort {
    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

    // Loaded lazily on first call so the constructor never touches Redis.
    // This keeps `Application.startup` -> `RedisHealthProbe` as the single
    // place where startup-time Redis I/O can fail and emit the FATAL banner.
    @Volatile private var scriptSha: String? = null

    override fun isAllowed(key: String): Boolean {
        val redisKey = redisKey(key)
        val now = System.currentTimeMillis()
        val member = "$now:${UUID.randomUUID().toString().take(8)}"

        return try {
            val result = evalSlidingWindow(redisKey, now, member)
            result[0] == 1L
        } catch (e: RedisException) {
            if (failOpen) {
                log.warn("Redis unreachable, failing open: {}", e.message)
                true
            } else {
                log.warn("Redis unreachable, failing closed: {}", e.message)
                false
            }
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
            doEval(ensureScriptLoaded(), redisKey, nowStr, window, maxStr, member)
        } catch (e: RedisNoScriptException) {
            val reloaded = commands.scriptLoad(SCRIPT).also { scriptSha = it }
            doEval(reloaded, redisKey, nowStr, window, maxStr, member)
        }
    }

    private fun ensureScriptLoaded(): String {
        scriptSha?.let { return it }
        return synchronized(this) {
            scriptSha ?: commands.scriptLoad(SCRIPT).also { scriptSha = it }
        }
    }

    private fun doEval(
        sha: String,
        redisKey: String,
        now: String,
        window: String,
        max: String,
        member: String,
    ): List<Long> {
        @Suppress("UNCHECKED_CAST")
        val raw =
            commands.evalsha<List<*>>(
                sha,
                ScriptOutputType.MULTI,
                arrayOf(redisKey),
                now,
                window,
                max,
                member,
            )
        return raw.map { element ->
            (element as? Long) ?: throw RedisException("Unexpected sliding_window.lua return type: $element")
        }
    }

    companion object {
        private val SCRIPT: String =
            RedisRateLimiter::class.java.classLoader
                .getResourceAsStream("redis/sliding_window.lua")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("redis/sliding_window.lua not found on classpath")
    }
}
