package com.kauth.adapter.web.plugin

import com.kauth.fakes.FakeCorsPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsOriginCacheTest {
    @Test
    fun `hits delegate on first call and returns same policy on second call within TTL`() {
        val delegate =
            FakeCorsPort().apply {
                setPolicy("oriana", setOf("https://desk.example.com"))
            }
        var now = 0L
        val cache = CorsOriginCache(delegate, ttlMillis = 60_000, clock = { now })

        val first = cache.policyForTenant("oriana")
        now = 30_000
        val second = cache.policyForTenant("oriana")

        assertEquals(first, second)
        assertEquals(setOf("https://desk.example.com"), first?.allowedOrigins)
    }

    @Test
    fun `reloads from delegate after TTL expires`() {
        val delegate =
            FakeCorsPort().apply {
                setPolicy("oriana", setOf("https://old.example.com"))
            }
        var now = 0L
        val cache = CorsOriginCache(delegate, ttlMillis = 60_000, clock = { now })

        cache.policyForTenant("oriana")
        delegate.setPolicy("oriana", setOf("https://new.example.com"))
        now = 60_001
        val reloaded = cache.policyForTenant("oriana")

        assertEquals(setOf("https://new.example.com"), reloaded?.allowedOrigins)
    }

    @Test
    fun `evict forces reload on next call`() {
        val delegate =
            FakeCorsPort().apply {
                setPolicy("oriana", setOf("https://old.example.com"))
            }
        val cache = CorsOriginCache(delegate, ttlMillis = Long.MAX_VALUE, clock = { 0L })

        cache.policyForTenant("oriana")
        delegate.setPolicy("oriana", setOf("https://new.example.com"))
        cache.invalidate("oriana")

        assertEquals(setOf("https://new.example.com"), cache.policyForTenant("oriana")?.allowedOrigins)
    }

    @Test
    fun `null tenant policies are cached`() {
        val delegate = FakeCorsPort()
        val cache = CorsOriginCache(delegate, ttlMillis = Long.MAX_VALUE, clock = { 0L })

        assertNull(cache.policyForTenant("ghost"))

        // Even if delegate gains a policy later, the null lookup is cached.
        delegate.setPolicy("ghost", setOf("https://new.example.com"))
        assertNull(cache.policyForTenant("ghost"))

        cache.invalidate("ghost")
        assertEquals(setOf("https://new.example.com"), cache.policyForTenant("ghost")?.allowedOrigins)
    }
}
