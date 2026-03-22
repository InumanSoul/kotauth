package com.kauth.adapter.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [healthRoutes].
 *
 * GET /health — liveness probe (always 200 if JVM is alive)
 * GET /health/ready — readiness probe (checks DB + config)
 *
 * No database is wired in the test engine, so the readiness probe
 * naturally exercises the "not_ready" path (DB unreachable).
 */
class HealthRoutesTest {
    // =========================================================================
    // GET /health — liveness
    // =========================================================================

    @Test
    fun `GET health returns 200 with status up`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { healthRoutes(baseUrl = "http://localhost:8080", encryptionAvailable = true) }
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"status\":\"up\"") || body.contains("\"status\": \"up\""))
        }

    @Test
    fun `GET health response contains ISO timestamp`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { healthRoutes(baseUrl = "http://localhost:8080", encryptionAvailable = true) }
            }

            val body = client.get("/health").bodyAsText()

            assertTrue(body.contains("\"timestamp\""))
            // ISO-8601 timestamps contain 'T' separator (e.g. 2026-03-21T12:00:00Z)
            assertTrue(body.contains("T"), "Timestamp must be ISO-8601 format")
        }

    // =========================================================================
    // GET /health/ready — readiness
    // =========================================================================

    @Test
    fun `GET health ready returns 503 when database is not available`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { healthRoutes(baseUrl = "http://localhost:8080", encryptionAvailable = true) }
            }

            val response = client.get("/health/ready")

            // No Exposed database is configured in the test engine, so
            // pingDatabase() throws → dbCheck.status = "error" → 503
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"status\":\"not_ready\"") || body.contains("\"status\": \"not_ready\""),
            )
        }

    @Test
    fun `GET health ready includes database check details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { healthRoutes(baseUrl = "http://localhost:8080", encryptionAvailable = true) }
            }

            val body = client.get("/health/ready").bodyAsText()

            assertTrue(body.contains("\"database\""), "Response must include database check")
            assertTrue(body.contains("\"config\""), "Response must include config check")
            assertTrue(body.contains("\"checks\""), "Response must include checks envelope")
        }

    // =========================================================================
    // Config check — warnings for non-HTTPS, missing secret key
    // =========================================================================

    @Test
    fun `checkConfig reports warning for non-HTTPS non-localhost base URL`() {
        val result = checkConfig("http://production.example.com", secretKeyPresent = true)

        assertEquals("warn", result.status)
        assertTrue(result.warnings.any { it.contains("HTTPS") })
        assertEquals(false, result.httpsEnforced)
    }

    @Test
    fun `checkConfig returns ok for localhost even without HTTPS`() {
        val result = checkConfig("http://localhost:8080", secretKeyPresent = true)

        // Localhost is exempted from the HTTPS warning
        assertTrue(
            result.warnings.none { it.contains("HTTPS") },
            "Localhost should not trigger HTTPS warning",
        )
    }

    @Test
    fun `checkConfig returns ok for HTTPS base URL`() {
        val result = checkConfig("https://auth.example.com", secretKeyPresent = true)

        assertEquals(true, result.httpsEnforced)
        assertTrue(
            result.warnings.none { it.contains("HTTPS") },
            "HTTPS URL should not trigger HTTPS warning",
        )
    }
}
