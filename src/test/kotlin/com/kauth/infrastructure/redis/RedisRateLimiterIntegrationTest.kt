package com.kauth.infrastructure.redis

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("redis")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateLimiterIntegrationTest {
    private lateinit var redis: GenericContainer<*>
    private lateinit var holder: RedisClientHolder

    @BeforeAll
    fun startRedis() {
        redis =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
        redis.start()
        holder =
            RedisClientFactory.create(
                url = "redis://${redis.host}:${redis.firstMappedPort}",
                username = null,
                password = null,
                connectTimeoutMs = 1_000,
                commandTimeoutMs = 500,
            )
    }

    @AfterAll
    fun stopRedis() {
        holder.shutdown()
        redis.stop()
    }

    @BeforeEach
    fun flush() {
        holder.commands.flushdb()
    }

    @Test
    fun `permits requests up to the limit then rejects`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 3,
                windowSeconds = 60,
                keyPrefix = "test",
            )

        assertTrue(limiter.isAllowed("alice"))
        assertTrue(limiter.isAllowed("alice"))
        assertTrue(limiter.isAllowed("alice"))
        assertFalse(limiter.isAllowed("alice"), "4th request should be rate-limited")
    }

    @Test
    fun `different keys have independent quotas`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 2,
                windowSeconds = 60,
                keyPrefix = "test",
            )

        assertTrue(limiter.isAllowed("alice"))
        assertTrue(limiter.isAllowed("alice"))
        assertFalse(limiter.isAllowed("alice"))

        assertTrue(limiter.isAllowed("bob"))
        assertTrue(limiter.isAllowed("bob"))
    }

    @Test
    fun `remaining decrements with each allowed request`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 5,
                windowSeconds = 60,
                keyPrefix = "test",
            )

        assertEquals(5, limiter.remaining("alice"))
        limiter.isAllowed("alice")
        limiter.isAllowed("alice")
        assertEquals(3, limiter.remaining("alice"))
    }

    @Test
    fun `reset restores full quota`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 2,
                windowSeconds = 60,
                keyPrefix = "test",
            )

        limiter.isAllowed("alice")
        limiter.isAllowed("alice")
        assertFalse(limiter.isAllowed("alice"))

        limiter.reset("alice")
        assertTrue(limiter.isAllowed("alice"))
    }

    @Test
    fun `key prefix isolates limiters sharing one Redis`() {
        val login =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 1,
                windowSeconds = 60,
                keyPrefix = "login",
            )
        val register =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 1,
                windowSeconds = 60,
                keyPrefix = "register",
            )

        assertTrue(login.isAllowed("user-1"))
        assertFalse(login.isAllowed("user-1"))
        assertTrue(register.isAllowed("user-1"), "register bucket is independent of login")
    }

    @Test
    fun `recovers from NOSCRIPT after SCRIPT FLUSH`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 3,
                windowSeconds = 60,
                keyPrefix = "test",
            )

        assertTrue(limiter.isAllowed("alice"))
        holder.commands.scriptFlush()
        assertTrue(limiter.isAllowed("alice"), "limiter must reload script transparently")
    }

    @Test
    fun `sliding window expires entries after the window passes`() {
        val limiter =
            RedisRateLimiter(
                commands = holder.commands,
                maxRequests = 2,
                windowSeconds = 1,
                keyPrefix = "test",
            )

        assertTrue(limiter.isAllowed("alice"))
        assertTrue(limiter.isAllowed("alice"))
        assertFalse(limiter.isAllowed("alice"))

        Thread.sleep(1_100)

        assertTrue(limiter.isAllowed("alice"), "quota should refill once window slides past")
    }
}
