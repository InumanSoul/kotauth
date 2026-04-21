package com.kauth.adapter.web.plugin

import com.kauth.domain.model.CorsDecision
import com.kauth.domain.service.CorsService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

class TenantCorsPluginConfig {
    lateinit var corsService: CorsService
    var tenantSlugParam: String = "slug"
    var allowedMethods: String = "GET, POST, OPTIONS"
    var allowedHeaders: String = "Authorization, Content-Type"
    var maxAge: String = "600"
}

val TenantCorsPlugin =
    createRouteScopedPlugin("TenantCorsPlugin", ::TenantCorsPluginConfig) {
        val service = pluginConfig.corsService
        val slugParam = pluginConfig.tenantSlugParam
        val allowedMethods = pluginConfig.allowedMethods
        val allowedHeaders = pluginConfig.allowedHeaders
        val maxAge = pluginConfig.maxAge
        val logger = LoggerFactory.getLogger("cors")

        onCall { call ->
            val origin = call.request.headers[HttpHeaders.Origin] ?: return@onCall
            if (origin == "null") return@onCall

            val slug = call.parameters[slugParam] ?: return@onCall
            val path = call.request.path()
            val isPreflight =
                call.request.httpMethod == HttpMethod.Options &&
                    call.request.headers.contains(HttpHeaders.AccessControlRequestMethod)

            val decision = service.decide(slug, origin, path)

            when (decision) {
                CorsDecision.Public -> {
                    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                    call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, OPTIONS")
                    call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, allowedHeaders)
                    call.response.headers.append(HttpHeaders.AccessControlMaxAge, maxAge)
                    if (isPreflight) call.respond(HttpStatusCode.NoContent)
                }

                is CorsDecision.Allowed -> {
                    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, decision.origin)
                    call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
                    call.response.headers.append(HttpHeaders.AccessControlAllowMethods, allowedMethods)
                    call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, allowedHeaders)
                    call.response.headers.append(HttpHeaders.AccessControlMaxAge, maxAge)
                    if (decision.allowCredentials) {
                        call.response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
                    }
                    if (isPreflight) call.respond(HttpStatusCode.NoContent)
                }

                CorsDecision.Denied -> {
                    logger.warn(
                        "cors_denied tenant={} origin={} method={} path={}",
                        slug,
                        origin,
                        call.request.httpMethod.value,
                        path,
                    )
                    if (isPreflight) call.respond(HttpStatusCode.Forbidden)
                }
            }
        }
    }
