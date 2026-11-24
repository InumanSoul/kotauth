package com.kauth.adapter.web

import com.kauth.adapter.web.api.respondProblem
import com.kauth.adapter.web.scim.scimAuthError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * A body that doesn't decode (unknown key, wrong type, bad JSON) or a malformed parameter is the
 * caller's mistake, so it gets a 400 in the surface's own envelope rather than the generic 500.
 */
internal fun StatusPagesConfig.badRequestsAsClientErrors(
    adminErrorPage: suspend ApplicationCall.(HttpStatusCode) -> Unit,
) {
    exception<BadRequestException> { call, cause ->
        val path = call.request.path()
        val detail = cause.cause?.message ?: cause.message ?: "The request could not be read."
        when {
            path.contains("/scim/v2") -> {
                val (status, body) = scimAuthError(HttpStatusCode.BadRequest, detail)
                call.respond(status, body)
            }
            path.contains("/api/v1") ->
                call.respondProblem(HttpStatusCode.BadRequest, "Invalid request body", detail)
            path.startsWith("/admin") -> call.adminErrorPage(HttpStatusCode.BadRequest)
            else ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_request", "error_description" to detail),
                )
        }
    }
}
