package com.kauth.adapter.web.plugin

import com.kauth.domain.service.CorsService
import com.kauth.fakes.FakeCorsPort
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantCorsPluginTest {
    private fun setup(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            val corsPort =
                FakeCorsPort().apply {
                    setPolicy(
                        tenantSlug = "oriana",
                        allowedOrigins = setOf("https://desk.oriana-platform.orb.local"),
                        allowCredentials = false,
                    )
                    setPolicy(
                        tenantSlug = "bff-tenant",
                        allowedOrigins = setOf("https://bff.example.com"),
                        allowCredentials = true,
                    )
                }
            val service = CorsService(corsPort)

            application {
                routing {
                    route("/t/{slug}") {
                        install(TenantCorsPlugin) { corsService = service }

                        get("/.well-known/openid-configuration") { call.respondText("{}") }
                        get("/protocol/openid-connect/certs") { call.respondText("{}") }
                        post("/protocol/openid-connect/token") { call.respondText("{}") }
                        get("/protocol/openid-connect/userinfo") { call.respondText("{}") }
                        options("/{...}") { call.respondText("") }
                    }
                }
            }
            block(client)
        }

    @Test
    fun `discovery endpoint returns wildcard origin for any requester`() =
        setup { client ->
            val response =
                client.get("/t/oriana/.well-known/openid-configuration") {
                    headers { append(HttpHeaders.Origin, "https://random.example.com") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `jwks endpoint returns wildcard origin`() =
        setup { client ->
            val response =
                client.get("/t/oriana/protocol/openid-connect/certs") {
                    headers { append(HttpHeaders.Origin, "https://random.example.com") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `discovery endpoint on unknown tenant still public`() =
        setup { client ->
            val response =
                client.get("/t/ghost/.well-known/openid-configuration") {
                    headers { append(HttpHeaders.Origin, "https://random.example.com") }
                }
            // Underlying route returns 404, but CORS plugin must still emit the wildcard
            // header so the browser sees an error body instead of an opaque CORS block.
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `token endpoint allows registered SPA origin`() =
        setup { client ->
            val response =
                client.post("/t/oriana/protocol/openid-connect/token") {
                    headers { append(HttpHeaders.Origin, "https://desk.oriana-platform.orb.local") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "https://desk.oriana-platform.orb.local",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
            assertEquals("Origin", response.headers[HttpHeaders.Vary])
            assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
        }

    @Test
    fun `token endpoint denies unregistered origin by omitting headers`() =
        setup { client ->
            val response =
                client.post("/t/oriana/protocol/openid-connect/token") {
                    headers { append(HttpHeaders.Origin, "https://evil.example.com") }
                }
            // Request proceeds to handler (browser enforces CORS client-side),
            // but no ACAO header is emitted — browser will block the response.
            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `preflight for allowed origin returns 204 with correct headers`() =
        setup { client ->
            val response =
                client.options("/t/oriana/protocol/openid-connect/token") {
                    headers {
                        append(HttpHeaders.Origin, "https://desk.oriana-platform.orb.local")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                        append(HttpHeaders.AccessControlRequestHeaders, "Authorization, Content-Type")
                    }
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(
                "https://desk.oriana-platform.orb.local",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
            assertEquals("GET, POST, OPTIONS", response.headers[HttpHeaders.AccessControlAllowMethods])
            assertEquals("Authorization, Content-Type", response.headers[HttpHeaders.AccessControlAllowHeaders])
            assertEquals("600", response.headers[HttpHeaders.AccessControlMaxAge])
        }

    @Test
    fun `preflight for denied origin returns 403 without CORS headers`() =
        setup { client ->
            val response =
                client.options("/t/oriana/protocol/openid-connect/token") {
                    headers {
                        append(HttpHeaders.Origin, "https://evil.example.com")
                        append(HttpHeaders.AccessControlRequestMethod, "POST")
                    }
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `allow_credentials=true tenant emits credentials header`() =
        setup { client ->
            val response =
                client.get("/t/bff-tenant/protocol/openid-connect/userinfo") {
                    headers { append(HttpHeaders.Origin, "https://bff.example.com") }
                }
            assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
            assertEquals(
                "https://bff.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
        }

    @Test
    fun `unknown tenant on client-scoped endpoint is denied`() =
        setup { client ->
            val response =
                client.post("/t/ghost/protocol/openid-connect/token") {
                    headers { append(HttpHeaders.Origin, "https://anywhere.example.com") }
                }
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `request without Origin header is ignored`() =
        setup { client ->
            val response = client.post("/t/oriana/protocol/openid-connect/token")
            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `origin null from sandboxed context is treated as no origin`() =
        setup { client ->
            val response =
                client.post("/t/oriana/protocol/openid-connect/token") {
                    headers { append(HttpHeaders.Origin, "null") }
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
}
