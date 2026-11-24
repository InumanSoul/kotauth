package com.kauth.adapter.web.plugin

import com.kauth.adapter.web.plugin.WorkspaceHostPolicy.Decision
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeTenantRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceHostPolicyTest {
    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme",
            issuerUrl = null,
            publicBaseUrl = "https://auth.acme.test",
        )
    private val beta = Tenant(id = TenantId(2), slug = "beta", displayName = "Beta", issuerUrl = null)
    private val bySlug = mapOf("acme" to acme, "beta" to beta)

    private fun decide(
        path: String,
        host: String,
        method: HttpMethod = HttpMethod.Get,
        hosts: Set<String> = setOf("auth.acme.test"),
    ) = WorkspaceHostPolicy.decide(method, path, "$path?x=1", host, bySlug::get, workspaceHosts = { hosts })

    @Test
    fun `a workspace page on the instance address moves to the workspace's domain, path and query kept`() {
        assertEquals(
            Decision.Redirect("https://auth.acme.test/t/acme/authorize?x=1"),
            decide("/t/acme/authorize", "sso.example.com"),
        )
        assertEquals(
            Decision.Redirect("https://auth.acme.test/t/acme/login?x=1"),
            decide("/t/acme/login", "sso.example.com", HttpMethod.Post),
        )
    }

    @Test
    fun `on its own domain the page is served, whatever the case of the host`() {
        assertEquals(Decision.Pass, decide("/t/acme/authorize", "Auth.Acme.test"))
    }

    @Test
    fun `servers keep reaching the API, SCIM, discovery and token endpoints on either address`() {
        listOf(
            "/t/acme/api/v1/users",
            "/t/acme/scim/v2/Users",
            "/t/acme/.well-known/openid-configuration",
            "/t/acme/protocol/openid-connect/token",
            "/t/acme/protocol/openid-connect/certs",
            "/t/acme/protocol/openid-connect/userinfo",
        ).forEach { assertEquals(Decision.Pass, decide(it, "sso.example.com"), it) }
    }

    @Test
    fun `a workspace's domain serves neither another workspace's pages nor the admin console`() {
        assertEquals(Decision.NotFound, decide("/t/beta/authorize", "auth.acme.test"))
        assertEquals(Decision.NotFound, decide("/admin/workspaces", "auth.acme.test"))
        assertEquals(Decision.Pass, decide("/admin/workspaces", "sso.example.com"))
        assertEquals(Decision.Pass, decide("/t/beta/authorize", "sso.example.com"))
    }

    @Test
    fun `an admin's browser stays on the instance address, where impersonation's portal cookie lives`() {
        val decision =
            WorkspaceHostPolicy.decide(
                HttpMethod.Get,
                "/t/acme/launcher",
                "/t/acme/launcher",
                "sso.example.com",
                bySlug::get,
                { setOf("auth.acme.test") },
                adminBrowser = true,
            )
        val onDomainForeign =
            WorkspaceHostPolicy.decide(
                HttpMethod.Get,
                "/t/beta/authorize",
                "/t/beta/authorize",
                "auth.acme.test",
                bySlug::get,
                { setOf("auth.acme.test") },
                adminBrowser = true,
            )

        assertEquals(Decision.Pass, decision)
        assertEquals(Decision.NotFound, onDomainForeign)
    }

    @Test
    fun `a domain change on this replica applies at once, without waiting for the cache to expire`() {
        val tenants = FakeTenantRepository().apply { add(beta) }
        val cache = WorkspaceHostCache(tenants)
        val before = cache.hosts()

        tenants.update(tenants.findBySlug("beta")!!.copy(publicBaseUrl = "https://id.beta.test"))
        val stale = cache.hosts()
        cache.invalidate()

        assertEquals(emptySet(), before)
        assertEquals(emptySet(), stale)
        assertEquals(setOf("id.beta.test"), cache.hosts())
    }

    @Test
    fun `nothing is enforced while no workspace has a domain`() {
        assertEquals(Decision.Pass, decide("/t/acme/authorize", "sso.example.com", hosts = emptySet()))
    }

    @Test
    fun `the installed plugin redirects with 308 and lets the right host through`() =
        testApplication {
            val tenants = FakeTenantRepository().apply { add(acme) }
            application {
                install(XForwardedHeaders) { useLastProxy() }
                install(WorkspaceHostPlugin) { tenantRepository = tenants }
                routing {
                    get("/t/{slug}/authorize") { call.respondText("page") }
                    post("/t/{slug}/protocol/openid-connect/token") { call.respondText("token") }
                }
            }
            val noFollow = createClient { followRedirects = false }

            val wrongHost =
                noFollow.get(
                    "/t/acme/authorize?client_id=x",
                ) { header("X-Forwarded-Host", "sso.example.com") }
            val rightHost = noFollow.get("/t/acme/authorize") { header("X-Forwarded-Host", "auth.acme.test") }
            val token =
                noFollow.post(
                    "/t/acme/protocol/openid-connect/token",
                ) { header("X-Forwarded-Host", "sso.example.com") }

            assertEquals(HttpStatusCode.PermanentRedirect, wrongHost.status)
            assertEquals("https://auth.acme.test/t/acme/authorize?client_id=x", wrongHost.headers["Location"])
            assertEquals(HttpStatusCode.OK, rightHost.status)
            assertEquals(HttpStatusCode.OK, token.status)
            val asAdmin =
                noFollow.get("/t/acme/authorize") {
                    header("X-Forwarded-Host", "sso.example.com")
                    header("Cookie", "KOTAUTH_ADMIN=session")
                }
            assertEquals(HttpStatusCode.OK, asAdmin.status)
        }
}
