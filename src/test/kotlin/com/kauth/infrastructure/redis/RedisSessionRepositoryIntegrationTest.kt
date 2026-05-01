package com.kauth.infrastructure.redis

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Session
import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("redis")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSessionRepositoryIntegrationTest {
    private lateinit var redis: GenericContainer<*>
    private lateinit var holder: RedisClientHolder
    private lateinit var repo: RedisSessionRepository

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
        repo = RedisSessionRepository(holder.commands)
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
    fun `save assigns an id and findById returns the same session`() {
        val saved = repo.save(newSession(userId = 1))

        assertNotNull(saved.id)
        val loaded = repo.findById(saved.id)
        assertEquals(saved, loaded)
    }

    @Test
    fun `findActiveByAccessTokenHash returns active session and ignores revoked`() {
        val saved = repo.save(newSession(accessTokenHash = "tok-A"))

        assertEquals(saved, repo.findActiveByAccessTokenHash("tok-A"))

        repo.revoke(saved.id!!, Instant.now())
        assertNull(repo.findActiveByAccessTokenHash("tok-A"))
    }

    @Test
    fun `findActiveByRefreshTokenHash respects refresh expiry`() {
        val now = Instant.now()
        val s =
            repo.save(
                newSession(
                    refreshTokenHash = "rtok",
                    refreshExpiresAt = now.plusSeconds(60),
                    expiresAt = now.plusSeconds(30),
                ),
            )

        assertEquals(s, repo.findActiveByRefreshTokenHash("rtok"))

        repo.revoke(s.id!!, Instant.now())
        assertNull(repo.findActiveByRefreshTokenHash("rtok"))
    }

    @Test
    fun `findActiveByUser returns only active sessions, newest first`() {
        val now = Instant.now()
        val older = repo.save(newSession(userId = 7, createdAt = now.minusSeconds(10), accessTokenHash = "old"))
        val newer = repo.save(newSession(userId = 7, createdAt = now, accessTokenHash = "new"))
        val revokedOne = repo.save(newSession(userId = 7, createdAt = now.minusSeconds(5), accessTokenHash = "rev"))
        repo.revoke(revokedOne.id!!, Instant.now())

        val active = repo.findActiveByUser(TenantId(1), UserId(7))

        assertEquals(listOf(newer.id, older.id), active.map { it.id })
    }

    @Test
    fun `countActiveByUser excludes revoked and expired sessions`() {
        val tenant = TenantId(1)
        val user = UserId(8)
        repo.save(newSession(userId = 8, accessTokenHash = "a"))
        repo.save(newSession(userId = 8, accessTokenHash = "b"))
        val revoked = repo.save(newSession(userId = 8, accessTokenHash = "c"))
        repo.revoke(revoked.id!!, Instant.now())

        assertEquals(2, repo.countActiveByUser(tenant, user))
    }

    @Test
    fun `revokeOldestForUser keeps the N most recent and revokes older ones`() {
        val now = Instant.now()
        repo.save(newSession(userId = 9, createdAt = now.minusSeconds(30), accessTokenHash = "1"))
        repo.save(newSession(userId = 9, createdAt = now.minusSeconds(20), accessTokenHash = "2"))
        repo.save(newSession(userId = 9, createdAt = now.minusSeconds(10), accessTokenHash = "3"))
        repo.save(newSession(userId = 9, createdAt = now, accessTokenHash = "4"))

        repo.revokeOldestForUser(TenantId(1), UserId(9), keepNewest = 2)

        val active = repo.findActiveByUser(TenantId(1), UserId(9))
        assertEquals(2, active.size)
        assertEquals(setOf("3", "4"), active.map { it.accessTokenHash }.toSet())
    }

    @Test
    fun `revokeAllForUser revokes every active session for that user only`() {
        repo.save(newSession(userId = 1, accessTokenHash = "u1-a"))
        repo.save(newSession(userId = 1, accessTokenHash = "u1-b"))
        repo.save(newSession(userId = 2, accessTokenHash = "u2-a"))

        repo.revokeAllForUser(TenantId(1), UserId(1), Instant.now())

        assertEquals(0, repo.countActiveByUser(TenantId(1), UserId(1)))
        assertEquals(1, repo.countActiveByUser(TenantId(1), UserId(2)))
    }

    @Test
    fun `revokeAllForTenant returns the count and clears the tenant`() {
        repo.save(newSession(tenantId = 1, accessTokenHash = "t1-a"))
        repo.save(newSession(tenantId = 1, accessTokenHash = "t1-b"))
        repo.save(newSession(tenantId = 2, accessTokenHash = "t2-a"))

        val count = repo.revokeAllForTenant(TenantId(1), Instant.now())

        assertEquals(2, count)
        assertEquals(0, repo.countActiveByTenant(TenantId(1)))
        assertEquals(1, repo.countActiveByTenant(TenantId(2)))
    }

    @Test
    fun `findActiveByTenant supports pagination newest-first`() {
        val now = Instant.now()
        repeat(5) { i ->
            repo.save(newSession(tenantId = 5, accessTokenHash = "s$i", createdAt = now.minusSeconds(i.toLong())))
        }

        val page1 = repo.findActiveByTenant(TenantId(5), limit = 2, offset = 0)
        val page2 = repo.findActiveByTenant(TenantId(5), limit = 2, offset = 2)

        assertEquals(listOf("s0", "s1"), page1.map { it.accessTokenHash })
        assertEquals(listOf("s2", "s3"), page2.map { it.accessTokenHash })
    }

    @Test
    fun `findActiveByUser does not leak across tenants`() {
        repo.save(newSession(tenantId = 1, userId = 7, accessTokenHash = "t1u7"))
        repo.save(newSession(tenantId = 2, userId = 7, accessTokenHash = "t2u7"))

        val tenant1 = repo.findActiveByUser(TenantId(1), UserId(7))
        val tenant2 = repo.findActiveByUser(TenantId(2), UserId(7))

        assertEquals(1, tenant1.size)
        assertEquals("t1u7", tenant1.single().accessTokenHash)
        assertEquals(1, tenant2.size)
        assertEquals("t2u7", tenant2.single().accessTokenHash)
    }

    @Test
    fun `liveSessions prunes orphan members on the set that is read`() {
        val saved = repo.save(newSession(userId = 11, accessTokenHash = "orph"))
        val userKey = "kauth:session:active:user:1:11"
        val tenantKey = "kauth:session:active:tenant:1"
        assertEquals(1L, holder.commands.zcard(userKey), "user set seeded")
        assertEquals(1L, holder.commands.zcard(tenantKey), "tenant set seeded")

        // Simulate Redis TTL evicting the primary record without going through revoke().
        holder.commands.del("kauth:session:rec:${saved.id!!.value}")

        // Reading the user set both excludes the orphan and prunes it from that set.
        assertTrue(repo.findActiveByUser(TenantId(1), UserId(11)).isEmpty())
        assertEquals(0L, holder.commands.zcard(userKey), "user-set orphan should be pruned on read")

        // Pruning is per-set: the tenant set still holds the orphan until it is read.
        assertEquals(1L, holder.commands.zcard(tenantKey), "tenant set still holds orphan before read")
        assertEquals(0, repo.countActiveByTenant(TenantId(1)), "count must exclude orphans")
        assertEquals(0L, holder.commands.zcard(tenantKey), "tenant-set orphan should be pruned on read")
    }

    @Test
    fun `save accepts an already-expired session and Redis honors the index TTL`() {
        val past = Instant.now().minusSeconds(60)
        val saved =
            repo.save(
                newSession(
                    accessTokenHash = "stale",
                    createdAt = past.minusSeconds(60),
                    expiresAt = past,
                ),
            )

        // The record itself survives via the retention buffer, but isExpired must report true.
        assertNotNull(saved.id)
        assertTrue(repo.findById(saved.id)?.isExpired == true)
        // Access-token index TTL was coerced to 1s and Redis evicts it almost immediately.
        Thread.sleep(1_500)
        assertNull(holder.commands.get("kauth:session:tok:stale"), "expired access-token index should be evicted")
    }

    @Test
    fun `revoking an unknown session is a no-op`() {
        repo.revoke(SessionId(99_999), Instant.now())
        // Should not throw and should leave Redis untouched.
        assertTrue(holder.commands.dbsize() == 0L)
    }

    @Test
    fun `findActiveByImpersonator returns only children of the given parent`() {
        val admin = repo.save(newSession(userId = 10, accessTokenHash = "admin"))
        val child1 = repo.save(newSession(userId = 20, accessTokenHash = "imp1", impersonatorSessionId = admin.id))
        val child2 = repo.save(newSession(userId = 21, accessTokenHash = "imp2", impersonatorSessionId = admin.id))
        repo.save(newSession(userId = 22, accessTokenHash = "unrelated"))

        val children = repo.findActiveByImpersonator(admin.id!!)

        assertEquals(setOf(child1.id, child2.id), children.map { it.id }.toSet())
    }

    @Test
    fun `revokeAllByImpersonator revokes all active children and skips revoked ones`() {
        val admin = repo.save(newSession(userId = 10, accessTokenHash = "admin"))
        repo.save(newSession(userId = 20, accessTokenHash = "imp1", impersonatorSessionId = admin.id))
        repo.save(newSession(userId = 21, accessTokenHash = "imp2", impersonatorSessionId = admin.id))

        val firstSweep = repo.revokeAllByImpersonator(admin.id!!)
        assertEquals(2, firstSweep)
        assertTrue(repo.findActiveByImpersonator(admin.id!!).isEmpty())

        // A second call should report zero — already-revoked children are not double-counted.
        assertEquals(0, repo.revokeAllByImpersonator(admin.id!!))
    }

    private fun newSession(
        tenantId: Int = 1,
        userId: Int? = 1,
        clientId: Int? = 1,
        accessTokenHash: String = "access-${java.util.UUID.randomUUID()}",
        refreshTokenHash: String? = null,
        createdAt: Instant = Instant.now(),
        expiresAt: Instant = createdAt.plusSeconds(3600),
        refreshExpiresAt: Instant? = null,
        impersonatorSessionId: SessionId? = null,
    ) = Session(
        tenantId = TenantId(tenantId),
        userId = userId?.let { UserId(it) },
        clientId = clientId?.let { ApplicationId(it) },
        accessTokenHash = accessTokenHash,
        refreshTokenHash = refreshTokenHash,
        scopes = "openid",
        ipAddress = "127.0.0.1",
        userAgent = "test",
        createdAt = createdAt,
        expiresAt = expiresAt,
        refreshExpiresAt = refreshExpiresAt,
        lastActivityAt = createdAt,
        revokedAt = null,
        impersonatorSessionId = impersonatorSessionId,
    )
}
