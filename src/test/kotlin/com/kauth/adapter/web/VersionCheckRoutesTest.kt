package com.kauth.adapter.web

import com.kauth.infrastructure.VersionCheckService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionCheckRoutesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `GET health version returns 200 when disabled`() =
        testApplication {
            val service =
                VersionCheckService(
                    currentVersion = "1.3.3",
                    manifestUrl = "http://localhost/nope",
                    enabled = false,
                    scope = scope,
                )
            application {
                install(ContentNegotiation) { json() }
                routing { versionCheckRoutes(service) }
            }

            val response = client.get("/health/version")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"enabled\":false") || body.contains("\"enabled\": false"))
            assertTrue(body.contains("\"currentVersion\":\"1.3.3\"") || body.contains("\"currentVersion\": \"1.3.3\""))
            assertTrue(body.contains("\"updateAvailable\":false") || body.contains("\"updateAvailable\": false"))
        }

    @Test
    fun `GET health version returns 200 when enabled but no check yet`() =
        testApplication {
            val service =
                VersionCheckService(
                    currentVersion = "1.3.3",
                    manifestUrl = "http://localhost/nope",
                    enabled = true,
                    scope = scope,
                )
            application {
                install(ContentNegotiation) { json() }
                routing { versionCheckRoutes(service) }
            }

            val response = client.get("/health/version")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"enabled\":true") || body.contains("\"enabled\": true"))
            assertTrue(body.contains("\"updateAvailable\":false") || body.contains("\"updateAvailable\": false"))
        }

    @Test
    fun `GET health version returns update available with urgency`() =
        testApplication {
            val manifest =
                """{"version":"2.0.0","urgency":"security","releaseUrl":"https://example.com/v2"}"""
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

            application {
                install(ContentNegotiation) { json() }
                routing { versionCheckRoutes(service) }
            }

            val response = client.get("/health/version")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"updateAvailable\":true") || body.contains("\"updateAvailable\": true"))
            assertTrue(body.contains("\"latestVersion\":\"2.0.0\"") || body.contains("\"latestVersion\": \"2.0.0\""))
            assertTrue(body.contains("\"urgency\":\"security\"") || body.contains("\"urgency\": \"security\""))
            assertTrue(
                body.contains("\"releaseUrl\":\"https://example.com/v2\"") ||
                    body.contains("\"releaseUrl\": \"https://example.com/v2\""),
            )
            assertTrue(body.contains("\"checkedAt\""))
        }

    @Test
    fun `GET health version returns application json content type`() =
        testApplication {
            val service =
                VersionCheckService(
                    currentVersion = "1.3.3",
                    manifestUrl = "http://localhost/nope",
                    enabled = false,
                    scope = scope,
                )
            application {
                install(ContentNegotiation) { json() }
                routing { versionCheckRoutes(service) }
            }

            val response = client.get("/health/version")

            assertTrue(
                response.headers["Content-Type"]?.contains(ContentType.Application.Json.toString()) == true,
            )
        }
}
