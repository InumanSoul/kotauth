package com.kauth.adapter.web.auth

import com.kauth.domain.model.SecurityConfig
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.SelfServiceResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Endpoint the user lands on when an admin has forced a password change.
 * Shares the [AuthTenantAttr] tenant-resolution plugin with the rest of
 * `/t/{slug}/…`.
 *
 * Mirrors the invite-accept flow: GET renders the form, POST consumes a
 * TEMP_PASSWORD token via [CredentialFlowService.confirmForcedPasswordChange].
 */
internal fun Route.forceChangePasswordRoutes(
    credentialFlowService: CredentialFlowService,
    rateLimiter: RateLimiterPort,
) {
    get("/change-password") {
        val ctx = call.attributes[AuthTenantAttr]
        val token = call.request.queryParameters["token"] ?: ""

        if (token.isBlank()) {
            return@get call.respondRedirect("/t/${ctx.slug}/account/login")
        }

        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.forceChangePasswordPage(
                ctx.slug,
                ctx.viewContext,
                token = token,
                passwordPolicy = policy,
            ),
        )
    }

    post("/change-password") {
        val ctx = call.attributes[AuthTenantAttr]
        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        val ipAddress = call.request.local.remoteAddress
        val params = call.receiveParameters()
        val token = params["token"] ?: ""
        val newPassword = params["new_password"] ?: ""
        val confirmPassword = params["confirm_password"] ?: ""

        val rateLimitKey = "temp-pw-change:$ipAddress:${ctx.slug}"
        if (!rateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.forceChangePasswordPage(
                    ctx.slug,
                    ctx.viewContext,
                    token = token,
                    error = "Too many attempts. Please wait a few minutes.",
                    passwordPolicy = policy,
                ),
            )
        }

        when (
            val result =
                credentialFlowService.confirmForcedPasswordChange(token, newPassword, confirmPassword)
        ) {
            is SelfServiceResult.Success ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.forceChangePasswordPage(
                        ctx.slug,
                        ctx.viewContext,
                        token = token,
                        success = true,
                        passwordPolicy = policy,
                    ),
                )
            is SelfServiceResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.forceChangePasswordPage(
                        ctx.slug,
                        ctx.viewContext,
                        token = token,
                        error = result.error.message,
                        passwordPolicy = policy,
                    ),
                )
        }
    }
}
