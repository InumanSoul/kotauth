package com.kauth.adapter.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests verifying the demo banner is present/absent in rendered pages.
 *
 * Uses the welcome route (GET /) as the test surface because it has the
 * fewest dependencies (no auth session, no DB for production mode).
 */
class DemoBannerRoutesTest {
    @AfterTest
    fun reset() {
        DemoConfig.enabled = false
    }

    @Test
    fun `welcome page includes demo-banner when DemoConfig is enabled`() =
        testApplication {
            DemoConfig.enabled = true
            application {
                routing {
                    welcomeRoutes(
                        baseUrl = "http://localhost:8080",
                        appInfo = testAppInfo(),
                        startTime = System.currentTimeMillis(),
                        isDevelopment = false,
                        encryptionAvailable = true,
                    )
                }
            }

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("demo-banner"), "Banner div should be present")
            assertTrue(body.contains("badge badge--warn"), "Demo badge should be rendered")
            assertTrue(body.contains("sarah.chen"), "Acme credentials should appear")
        }

    @Test
    fun `welcome page excludes demo-banner when DemoConfig is disabled`() =
        testApplication {
            DemoConfig.enabled = false
            application {
                routing {
                    welcomeRoutes(
                        baseUrl = "http://localhost:8080",
                        appInfo = testAppInfo(),
                        startTime = System.currentTimeMillis(),
                        isDevelopment = false,
                        encryptionAvailable = true,
                    )
                }
            }

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("demo-banner"), "Banner should not appear when disabled")
        }

    @Test
    fun `welcome page still renders normally with banner enabled`() =
        testApplication {
            DemoConfig.enabled = true
            application {
                routing {
                    welcomeRoutes(
                        baseUrl = "http://localhost:8080",
                        appInfo = testAppInfo(),
                        startTime = System.currentTimeMillis(),
                        isDevelopment = false,
                        encryptionAvailable = true,
                    )
                }
            }

            val body = client.get("/").bodyAsText()

            assertTrue(body.contains("welcome-shell"), "Welcome shell should still render")
            assertTrue(body.contains("Kotauth"), "Page title should still appear")
        }

    private fun testAppInfo() =
        AppInfo(
            version = "1.0.0-test",
            ktorVersion = "2.3.12",
        )
}
