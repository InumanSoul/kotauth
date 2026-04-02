package com.kauth.adapter.web

import com.kauth.infrastructure.VersionCheckService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
}
