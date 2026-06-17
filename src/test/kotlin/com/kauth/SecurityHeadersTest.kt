package com.kauth

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecurityHeadersTest {
    @Test
    fun `DefaultHeaders with empty Server overrides Ktor's default fingerprint`() =
        testApplication {
            application {
                install(DefaultHeaders) {
                    header("X-Content-Type-Options", "nosniff")
                    header(HttpHeaders.Server, "")
                }
                routing {
                    get("/probe") { call.respondText("ok") }
                }
            }
            val response = client.get("/probe")
            val server = response.headers["Server"] ?: ""
            assertEquals("", server, "Server header must not advertise the engine — was: $server")
            assertNotEquals("Ktor/", server.take(5))
        }
}
