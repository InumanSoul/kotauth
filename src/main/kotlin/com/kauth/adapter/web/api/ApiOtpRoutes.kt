package com.kauth.adapter.web.api

import com.kauth.domain.model.ApiScope
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.OtpError
import com.kauth.domain.service.OtpSendResult
import com.kauth.domain.service.OtpVerifyResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

private const val CONSTANT_TIME_TARGET_MS = 800L
private const val PER_EMAIL_RATE_PREFIX = "otp_email"
private const val PER_IP_RATE_PREFIX = "otp_ip"

internal fun Route.apiOtpRoutes(
    emailOtpService: EmailOtpService,
    perEmailLimiter: RateLimiterPort,
    perIpLimiter: RateLimiterPort,
) {
    route("/auth") {
        post("/send-otp") {
            requireScope(call, ApiScope.AUTH_SEND_OTP) ?: return@post
            val tenantSlug =
                call.parameters["tenantSlug"]
                    ?: return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing tenant slug",
                        "tenantSlug path parameter is required.",
                    )
            val body = call.receive<SendOtpRequest>()
            val normalizedEmail = body.email.trim().lowercase()
            if (normalizedEmail.isBlank() || "@" !in normalizedEmail) {
                return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Invalid email",
                    "A non-empty email address is required.",
                )
            }
            val ip = call.request.local.remoteHost

            if (!perEmailLimiter.isAllowed("$PER_EMAIL_RATE_PREFIX:$tenantSlug:$normalizedEmail") ||
                !perIpLimiter.isAllowed("$PER_IP_RATE_PREFIX:$ip")
            ) {
                return@post call.respondProblem(
                    HttpStatusCode.TooManyRequests,
                    "Rate limit exceeded",
                    "Too many OTP requests. Wait a few minutes before retrying.",
                )
            }

            constantTimePadded {
                when (
                    val result =
                        emailOtpService.sendOtp(
                            tenantSlug = tenantSlug,
                            email = normalizedEmail,
                            originatingClientId = body.originatingClientId,
                            resources = body.resources,
                            ipAddress = ip,
                        )
                ) {
                    is OtpSendResult.Success ->
                        call.respond(
                            HttpStatusCode.Accepted,
                            SendOtpResponse(
                                challengeId = result.challengeId,
                                expiresAt = DateTimeFormatter.ISO_INSTANT.format(result.expiresAt),
                            ),
                        )
                    is OtpSendResult.Failure -> call.respondOtpError(result.error)
                }
            }
        }

        post("/verify-otp") {
            requireScope(call, ApiScope.AUTH_VERIFY_OTP) ?: return@post
            val tenantSlug =
                call.parameters["tenantSlug"]
                    ?: return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing tenant slug",
                        "tenantSlug path parameter is required.",
                    )
            val body = call.receive<VerifyOtpRequest>()
            val ip = call.request.local.remoteHost

            when (
                val result =
                    emailOtpService.verifyOtp(
                        tenantSlug = tenantSlug,
                        challengeId = body.challengeId,
                        code = body.otp,
                        ipAddress = ip,
                    )
            ) {
                is OtpVerifyResult.Success ->
                    call.respond(
                        HttpStatusCode.OK,
                        VerifyOtpResponse(
                            authorizationCode = result.authorizationCode,
                            expiresAt = DateTimeFormatter.ISO_INSTANT.format(result.codeExpiresAt),
                            redirectUri = result.redirectUri,
                        ),
                    )
                is OtpVerifyResult.Failure -> call.respondOtpError(result.error)
            }
        }
    }
}

private suspend fun constantTimePadded(block: suspend () -> Unit) {
    val start = System.nanoTime()
    block()
    val elapsed = (System.nanoTime() - start) / 1_000_000
    val remaining = CONSTANT_TIME_TARGET_MS - elapsed
    if (remaining > 0) delay(remaining)
}

private suspend fun ApplicationCall.respondOtpError(error: OtpError) {
    when (error) {
        OtpError.TenantNotFound ->
            respondProblem(HttpStatusCode.NotFound, "Tenant not found", "No workspace with that slug exists.")
        OtpError.ChallengeExpired ->
            respondProblem(HttpStatusCode.Gone, "Challenge expired", "Request a new code.")
        OtpError.InvalidOtp ->
            respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid code", error.code)
        OtpError.TooManyAttempts ->
            respondProblem(HttpStatusCode.UnprocessableEntity, "Too many attempts", error.code)
        OtpError.InvalidClient ->
            respondProblem(
                HttpStatusCode.UnprocessableEntity,
                "Invalid client",
                "The originating client is unknown, disabled, or has no redirect URI registered.",
            )
    }
}

@Serializable
data class SendOtpRequest(
    val email: String,
    val originatingClientId: String? = null,
    val resources: List<String> = emptyList(),
)

@Serializable
data class SendOtpResponse(
    val challengeId: String,
    val expiresAt: String,
)

@Serializable
data class VerifyOtpRequest(
    val challengeId: String,
    val otp: String,
)

@Serializable
data class VerifyOtpResponse(
    val authorizationCode: String,
    val expiresAt: String,
    val redirectUri: String,
)
