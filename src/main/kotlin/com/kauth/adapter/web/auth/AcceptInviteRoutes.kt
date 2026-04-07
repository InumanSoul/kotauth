package com.kauth.adapter.web.auth

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.UserSelfServiceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.acceptInviteRoutes(
    selfServiceService: UserSelfServiceService,
    rateLimiter: RateLimiterPort,
) {
    get("/accept-invite") {
        val ctx = call.attributes[AuthTenantAttr]
        val token = call.request.queryParameters["token"] ?: ""

        if (token.isBlank()) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }

        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.acceptInvitePage(
                ctx.slug,
                ctx.theme,
                ctx.workspaceName,
                token = token,
                passwordPolicy = policy,
            ),
        )
    }

    post("/accept-invite") {
        val ctx = call.attributes[AuthTenantAttr]
        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        val ipAddress = call.request.local.remoteAddress
        val params = call.receiveParameters()
        val token = params["token"] ?: ""
        val newPassword = params["new_password"] ?: ""
        val confirmPassword = params["confirm_password"] ?: ""

        val rateLimitKey = "invite:$ipAddress:${ctx.slug}"
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.acceptInvitePage(
                    ctx.slug,
                    ctx.theme,
                    ctx.workspaceName,
                    token = token,
                    error = "Too many attempts. Please wait a few minutes.",
                    passwordPolicy = policy,
                ),
            )
        }

        when (val result = selfServiceService.confirmAcceptInvite(token, newPassword, confirmPassword)) {
            is SelfServiceResult.Success ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.acceptInvitePage(
                        ctx.slug,
                        ctx.theme,
                        ctx.workspaceName,
                        token = token,
                        success = true,
                        passwordPolicy = policy,
                    ),
                )
            is SelfServiceResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.acceptInvitePage(
                        ctx.slug,
                        ctx.theme,
                        ctx.workspaceName,
                        token = token,
                        error = result.error.message,
                        passwordPolicy = policy,
                    ),
                )
        }
    }
}
