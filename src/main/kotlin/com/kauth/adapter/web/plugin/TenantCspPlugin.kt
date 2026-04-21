package com.kauth.adapter.web.plugin

import com.kauth.domain.port.CorsPort
import io.ktor.server.application.createRouteScopedPlugin

private const val CSP_HEADER = "Content-Security-Policy"

class TenantCspPluginConfig {
    lateinit var corsPort: CorsPort
    var tenantSlugParam: String = "slug"
}

val TenantCspPlugin =
    createRouteScopedPlugin("TenantCspPlugin", ::TenantCspPluginConfig) {
        val corsPort = pluginConfig.corsPort
        val slugParam = pluginConfig.tenantSlugParam

        onCall { call ->
            val slug = call.parameters[slugParam] ?: return@onCall
            val origins = corsPort.policyForTenant(slug)?.allowedOrigins.orEmpty()
            call.response.headers.append(CSP_HEADER, buildCspPolicy(origins))
        }
    }
