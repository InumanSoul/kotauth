package com.kauth.adapter.web

import com.kauth.domain.port.TenantRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * WebAuthn Related Origin Requests: passkeys are bound to the instance host (the RP ID), and a
 * browser on a workspace's own domain fetches this list from that host to learn it may use them.
 */
internal fun Route.webAuthnRelatedOriginsRoutes(tenantRepository: TenantRepository) {
    get("/.well-known/webauthn") {
        val origins =
            tenantRepository
                .findAll()
                .mapNotNull { it.publicBaseUrl }
                .distinct()
                .sorted()
        val body = buildJsonObject { putJsonArray("origins") { origins.forEach { add(it) } } }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respondText(Json.encodeToString(body), ContentType.Application.Json)
    }
}
