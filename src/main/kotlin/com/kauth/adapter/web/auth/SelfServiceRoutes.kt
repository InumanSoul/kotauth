package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.SelfServiceResult
import com.kauth.domain.service.UserSelfServiceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.selfServiceRoutes(
    tenantRepository: TenantRepository,
    selfServiceService: UserSelfServiceService,
    registerRateLimiter: RateLimiterPort,
) {
    get("/forgot-password") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val sent = call.request.queryParameters["sent"] == "true"
        val reason = call.request.queryParameters["reason"]
        val errorMsg =
            if (reason == "expired") {
                "Your password has expired. Enter your email below to receive a reset link."
            } else {
                null
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.forgotPasswordPage(slug, theme, workspaceName, error = errorMsg, sent = sent),
        )
    }

    post("/forgot-password") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val params = call.receiveParameters()
        val email = params["email"]?.trim() ?: ""
        val ipAddress = call.request.local.remoteAddress
        val callbackBaseUrl = call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

        val rateLimitKey = "forgot:$ipAddress"
        if (!registerRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondRedirect("/t/$slug/forgot-password?sent=true")
        }

        selfServiceService.initiateForgotPassword(email, slug, callbackBaseUrl, ipAddress)
        call.respondRedirect("/t/$slug/forgot-password?sent=true")
    }

    get("/reset-password") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val token = call.request.queryParameters["token"] ?: ""

        if (token.isBlank()) {
            return@get call.respondRedirect("/t/$slug/forgot-password")
        }
        call.respondHtml(HttpStatusCode.OK, AuthView.resetPasswordPage(slug, theme, workspaceName, token = token))
    }

    post("/reset-password") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val params = call.receiveParameters()
        val token = params["token"] ?: ""
        val newPassword = params["new_password"] ?: ""
        val confirmPassword = params["confirm_password"] ?: ""

        when (val result = selfServiceService.confirmPasswordReset(token, newPassword, confirmPassword)) {
            is SelfServiceResult.Success ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.resetPasswordPage(slug, theme, workspaceName, token = token, success = true),
                )
            is SelfServiceResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AuthView.resetPasswordPage(
                        slug,
                        theme,
                        workspaceName,
                        token = token,
                        error = result.error.message,
                    ),
                )
        }
    }

    get("/verify-email") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val token = call.request.queryParameters["token"] ?: ""

        if (token.isBlank()) {
            return@get call.respondHtml(
                HttpStatusCode.BadRequest,
                AuthView.verifyEmailPage(
                    slug,
                    theme,
                    workspaceName,
                    success = false,
                    message = "Verification link is missing or invalid.",
                ),
            )
        }

        when (val result = selfServiceService.confirmEmailVerification(token)) {
            is SelfServiceResult.Success ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.verifyEmailPage(
                        slug,
                        theme,
                        workspaceName,
                        success = true,
                        message = "Your email address has been verified successfully.",
                    ),
                )
            is SelfServiceResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.BadRequest,
                    AuthView.verifyEmailPage(
                        slug,
                        theme,
                        workspaceName,
                        success = false,
                        message = result.error.message,
                    ),
                )
        }
    }
}
