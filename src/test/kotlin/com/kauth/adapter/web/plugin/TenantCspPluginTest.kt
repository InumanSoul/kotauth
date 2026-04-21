package com.kauth.adapter.web.plugin

import com.kauth.fakes.FakeCorsPort
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TenantCspPluginTest {
    private fun setup(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            val corsPort =
                FakeCorsPort().apply {
                    setPolicy(
                        tenantSlug = "oriana",
                        allowedOrigins =
                            setOf(
                                "https://desk.oriana-platform.orb.local",
                                "http://localhost:3000",
                            ),
                    )
                    setPolicy(tenantSlug = "empty-tenant", allowedOrigins = emptySet())
                }

            application {
                routing {
                    route("/t/{slug}") {
                        install(TenantCspPlugin) { this.corsPort = corsPort }
                        get("/authorize") { call.respondText("login-page") }
                    }
                }
            }
            block(client)
        }

    @Test
    fun `known tenant CSP includes registered SPA origins in form-action`() =
        setup { client ->
            val response = client.get("/t/oriana/authorize")
            assertEquals(HttpStatusCode.OK, response.status)

            val csp = response.headers["Content-Security-Policy"]
            assertTrue(csp != null, "CSP header should be present")
            assertTrue(
                csp.contains("form-action 'self' https://desk.oriana-platform.orb.local http://localhost:3000") ||
                    csp.contains("form-action 'self' http://localhost:3000 https://desk.oriana-platform.orb.local"),
                "form-action should include self and both registered origins, got: $csp",
            )
        }

    @Test
    fun `tenant with no registered origins gets only self in form-action`() =
        setup { client ->
            val response = client.get("/t/empty-tenant/authorize")
            val csp = response.headers["Content-Security-Policy"]
            assertTrue(csp != null)
            assertTrue(csp.contains("form-action 'self'"))
            assertTrue(!csp.contains("form-action 'self' "), "should not include extra origins, got: $csp")
        }

    @Test
    fun `unknown tenant still gets self-only form-action policy`() =
        setup { client ->
            val response = client.get("/t/ghost/authorize")
            val csp = response.headers["Content-Security-Policy"]
            assertTrue(csp != null)
            assertTrue(csp.contains("form-action 'self'"))
        }

    @Test
    fun `CSP includes the other standard directives`() =
        setup { client ->
            val response = client.get("/t/oriana/authorize")
            val csp = response.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("default-src 'self'"))
            assertTrue(csp.contains("script-src 'self'"))
            assertTrue(csp.contains("img-src 'self' data: https:"))
        }
}
