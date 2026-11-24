package com.kauth.adapter.web.plugin

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantRepository
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.net.URI
import java.time.Clock

/**
 * Keeps a workspace's pages on its public domain, and other pages off it.
 *
 * Hostnames are compared, not schemes or ports: behind a proxy that doesn't forward the scheme,
 * comparing whole origins would redirect forever.
 */
object WorkspaceHostPolicy {
    sealed class Decision {
        data object Pass : Decision()

        data class Redirect(
            val location: String,
        ) : Decision()

        data object NotFound : Decision()
    }

    // Called by servers and SDKs, which may still use the instance address during a migration.
    private val MACHINE_PATHS =
        listOf(
            "/api/",
            "/scim/",
            "/.well-known/",
            "/protocol/openid-connect/token",
            "/protocol/openid-connect/certs",
            "/protocol/openid-connect/userinfo",
            "/protocol/openid-connect/introspect",
            "/protocol/openid-connect/revoke",
        )

    private val WORKSPACE_PATH = Regex("^/t/([^/]+)(/.*)?$")

    fun decide(
        method: HttpMethod,
        path: String,
        uri: String,
        requestHost: String,
        tenantFor: (String) -> Tenant?,
        workspaceHosts: () -> Set<String>,
        // An admin's console session lives on the instance address, and so does impersonation:
        // its portal cookie is set there. Moving that browser to the domain would drop both.
        adminBrowser: Boolean = false,
    ): Decision {
        if (method == HttpMethod.Options) return Decision.Pass
        // No workspace has a domain: nothing to enforce, and no lookup per request.
        val known = workspaceHosts()
        if (known.isEmpty()) return Decision.Pass
        val host = requestHost.lowercase()
        val match = WORKSPACE_PATH.matchEntire(path)
        if (match == null) {
            // The admin console and instance pages never appear under a product's brand.
            return if (path.startsWith("/admin") && host in known) Decision.NotFound else Decision.Pass
        }
        val rest = match.groupValues[2]
        if (MACHINE_PATHS.any { rest.startsWith(it) }) return Decision.Pass
        val tenant = tenantFor(match.groupValues[1]) ?: return Decision.Pass
        val own = tenant.publicBaseUrl
        return when {
            own != null && hostOf(own) != host && !adminBrowser -> Decision.Redirect(own + uri)
            own == null && host in known -> Decision.NotFound
            else -> Decision.Pass
        }
    }

    fun hostOf(origin: String): String = URI(origin).host.lowercase()
}

/**
 * Which hosts belong to some workspace, reloaded every [ttlSeconds]. A domain change made on this
 * replica calls [invalidate] and applies at once; other replicas pick it up within the window.
 */
class WorkspaceHostCache(
    private val tenants: TenantRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ttlSeconds: Long = 30,
) {
    @Volatile private var hosts: Set<String> = emptySet()

    @Volatile private var loadedAt = Long.MIN_VALUE

    fun hosts(): Set<String> {
        val now = clock.millis()
        if (loadedAt == Long.MIN_VALUE || now - loadedAt > ttlSeconds * 1000) {
            hosts =
                tenants
                    .findAll()
                    .mapNotNull { it.publicBaseUrl }
                    .map(WorkspaceHostPolicy::hostOf)
                    .toSet()
            loadedAt = now
        }
        return hosts
    }

    fun invalidate() {
        loadedAt = Long.MIN_VALUE
    }
}

// Presence only: skipping a redirect grants nothing, since pages answer on every host.
private const val ADMIN_SESSION_COOKIE = "KOTAUTH_ADMIN"

class WorkspaceHostPluginConfig {
    lateinit var tenantRepository: TenantRepository
    var cache: WorkspaceHostCache? = null
}

val WorkspaceHostPlugin =
    createApplicationPlugin("WorkspaceHostPlugin", ::WorkspaceHostPluginConfig) {
        val tenants = pluginConfig.tenantRepository
        val cache = pluginConfig.cache ?: WorkspaceHostCache(tenants)

        onCall { call ->
            val decision =
                WorkspaceHostPolicy.decide(
                    method = call.request.httpMethod,
                    path = call.request.path(),
                    uri = call.request.uri,
                    requestHost = call.request.origin.serverHost,
                    tenantFor = tenants::findBySlug,
                    workspaceHosts = cache::hosts,
                    adminBrowser = call.request.cookies[ADMIN_SESSION_COOKIE] != null,
                )
            when (decision) {
                is WorkspaceHostPolicy.Decision.Redirect -> {
                    call.response.header("Location", decision.location)
                    call.respond(HttpStatusCode.PermanentRedirect)
                }
                WorkspaceHostPolicy.Decision.NotFound -> call.respond(HttpStatusCode.NotFound)
                WorkspaceHostPolicy.Decision.Pass -> Unit
            }
        }
    }
