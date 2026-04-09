package com.kauth.adapter.web.auth

import com.kauth.adapter.web.admin.resolvedBaseUrl
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

internal fun Route.selfServiceRoutes(
    selfServiceService: UserSelfServiceService,
    registerRateLimiter: RateLimiterPort,
) {
    get("/forgot-password") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
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
        val slug = call.attributes[AuthTenantAttr].slug
        val params = call.receiveParameters()
        val email = params["email"]?.trim() ?: ""
        val ipAddress = call.request.local.remoteAddress
        val callbackBaseUrl = call.resolvedBaseUrl()

        val rateLimitKey = "forgot:$ipAddress:$slug"
        if (!registerRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondRedirect("/t/$slug/forgot-password?sent=true")
        }

        selfServiceService.initiateForgotPassword(email, slug, callbackBaseUrl, ipAddress)
        call.respondRedirect("/t/$slug/forgot-password?sent=true")
    }

    get("/reset-password") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val token = call.request.queryParameters["token"] ?: ""

        if (token.isBlank()) {
            return@get call.respondRedirect("/t/$slug/forgot-password")
        }
        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.resetPasswordPage(slug, theme, workspaceName, token = token, passwordPolicy = policy),
        )
    }

    post("/reset-password") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
        val policy = ctx.tenant?.securityConfig ?: SecurityConfig()
        val ipAddress = call.request.local.remoteAddress
        val params = call.receiveParameters()
        val token = params["token"] ?: ""
        val newPassword = params["new_password"] ?: ""
        val confirmPassword = params["confirm_password"] ?: ""

        val rateLimitKey = "reset:$ipAddress:$slug"
        if (!registerRateLimiter.isAllowed(rateLimitKey)) {
            return@post call.respondHtml(
                HttpStatusCode.TooManyRequests,
                AuthView.resetPasswordPage(
                    slug,
                    theme,
                    workspaceName,
                    token = token,
                    error = "Too many attempts. Please wait a few minutes and try again.",
                    passwordPolicy = policy,
                ),
            )
        }

        when (val result = selfServiceService.confirmPasswordReset(token, newPassword, confirmPassword)) {
            is SelfServiceResult.Success ->
                call.respondHtml(
                    HttpStatusCode.OK,
                    AuthView.resetPasswordPage(
                        slug,
                        theme,
                        workspaceName,
                        token = token,
                        success = true,
                        passwordPolicy = policy,
                    ),
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
                        passwordPolicy = policy,
                    ),
                )
        }
    }

    get("/verify-email") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val theme = ctx.theme
        val workspaceName = ctx.workspaceName
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
