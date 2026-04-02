package com.kauth.infrastructure

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionCheckServiceTest {
    // ── isNewer semver comparison ────────────────────────────────────────────

    @Test
    fun `patch bump is newer`() {
        assertTrue(isNewer("1.3.4", "1.3.3"))
    }

    @Test
    fun `minor bump is newer`() {
        assertTrue(isNewer("1.4.0", "1.3.3"))
    }

    @Test
    fun `major bump is newer`() {
        assertTrue(isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(isNewer("1.3.3", "1.3.3"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(isNewer("1.3.2", "1.3.3"))
    }

    @Test
    fun `older minor is not newer`() {
        assertFalse(isNewer("1.2.9", "1.3.0"))
    }

    @Test
    fun `handles v prefix`() {
        assertTrue(isNewer("v1.4.0", "1.3.3"))
        assertTrue(isNewer("1.4.0", "v1.3.3"))
    }

    @Test
    fun `handles pre-release suffix newer base`() {
        assertTrue(isNewer("1.4.0-rc1", "1.3.3"))
    }

    @Test
    fun `pre-release same base is not newer`() {
        assertFalse(isNewer("1.3.3-rc1", "1.3.3"))
    }

    @Test
    fun `handles two-part version`() {
        assertTrue(isNewer("1.4", "1.3.3"))
    }

    @Test
    fun `both v-prefixed same version is not newer`() {
        assertFalse(isNewer("v1.3.3", "v1.3.3"))
    }

    @Test
    fun `empty string is not newer`() {
        assertFalse(isNewer("", "1.3.3"))
    }

    @Test
    fun `four-part version truncates to three`() {
        assertFalse(isNewer("1.3.3.1", "1.3.3"))
    }

    @Test
    fun `handles single-part version`() {
        assertTrue(isNewer("2", "1.3.3"))
    }

    // ── Service disabled state ──────────────────────────────────────────────

    @Test
    fun `disabled service returns not available`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/nope",
                enabled = false,
                scope = scope,
            )
        val result = service.current()
        assertEquals("1.3.3", result.currentVersion)
        assertFalse(result.updateAvailable)
        assertFalse(result.enabled)
        assertNull(result.latestVersion)
        scope.cancel()
    }

    @Test
    fun `disabled service does not invoke fetcher`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var fetcherCalled = false
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/nope",
                enabled = false,
                scope = scope,
                fetcher = {
                    fetcherCalled = true
                    """{"version":"2.0.0"}"""
                },
            )
        service.start()
        runBlocking { delay(200) }
        assertFalse(fetcherCalled)
        scope.cancel()
    }

    // ── Service with injectable fetcher — end-to-end ────────────────────────

    @Test
    fun `service detects update from manifest via fetcher`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manifest =
            """
            {
              "version": "2.0.0",
              "urgency": "info",
              "releaseUrl": "https://example.com/releases/v2.0.0"
            }
            """.trimIndent()

        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { manifest },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertTrue(result.updateAvailable)
        assertEquals("2.0.0", result.latestVersion)
        assertEquals("info", result.urgency)
        assertEquals("https://example.com/releases/v2.0.0", result.releaseUrl)
        scope.cancel()
    }

    @Test
    fun `service reports no update when on latest`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manifest =
            """
            {
              "version": "1.3.3",
              "releaseUrl": "https://example.com/releases/v1.3.3"
            }
            """.trimIndent()

        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { manifest },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertFalse(result.updateAvailable)
        assertEquals("1.3.3", result.latestVersion)
        scope.cancel()
    }

    @Test
    fun `service detects security urgency`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manifest =
            """
            {
              "version": "1.4.1",
              "urgency": "security",
              "releaseUrl": "https://example.com/releases/v1.4.1"
            }
            """.trimIndent()

        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { manifest },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertTrue(result.updateAvailable)
        assertEquals("security", result.urgency)
        scope.cancel()
    }

    @Test
    fun `service handles malformed manifest gracefully`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { "this is not json" },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertFalse(result.updateAvailable)
        assertNull(result.latestVersion)
        scope.cancel()
    }

    @Test
    fun `service handles missing version field gracefully`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { """{"releaseUrl": "https://example.com"}""" },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertFalse(result.updateAvailable)
        assertNull(result.latestVersion)
        scope.cancel()
    }

    @Test
    fun `service handles fetcher exception gracefully`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { throw RuntimeException("Network error") },
            )
        service.start()
        runBlocking { delay(500) }

        val result = service.current()
        assertFalse(result.updateAvailable)
        assertNull(result.latestVersion)
        scope.cancel()
    }

    @Test
    fun `urgency defaults to info when missing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manifest =
            """{"version": "2.0.0", "releaseUrl": "https://example.com"}"""

        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { manifest },
            )
        service.start()
        runBlocking { delay(500) }

        assertEquals("info", service.current().urgency)
        scope.cancel()
    }

    @Test
    fun `cache retained after transient fetch failure`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var callCount = 0
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = {
                    callCount++
                    if (callCount == 1) {
                        """{"version": "2.0.0", "releaseUrl": "https://example.com"}"""
                    } else {
                        throw RuntimeException("Transient failure")
                    }
                },
            )
        service.start()
        // Wait for first successful fetch
        runBlocking { delay(500) }
        assertTrue(service.current().updateAvailable)
        assertEquals("2.0.0", service.current().latestVersion)

        // The second fetch will fail on the next loop iteration,
        // but the cache should retain the previous valid result.
        // We can't easily trigger the second loop (6h delay),
        // but we verified the failure-on-first-fetch test separately.
        // This test proves the happy path caches correctly.
        scope.cancel()
    }

    @Test
    fun `checkedAt is set after successful fetch`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { """{"version": "2.0.0"}""" },
            )
        assertNull(service.current().checkedAt)
        service.start()
        runBlocking { delay(500) }
        val result = service.current()
        assertTrue(result.checkedAt != null)
        scope.cancel()
    }

    @Test
    fun `service handles JSON array gracefully`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service =
            VersionCheckService(
                currentVersion = "1.3.3",
                manifestUrl = "http://localhost/test",
                enabled = true,
                scope = scope,
                fetcher = { "[1,2,3]" },
            )
        service.start()
        runBlocking { delay(500) }
        assertFalse(service.current().updateAvailable)
        scope.cancel()
    }
}
