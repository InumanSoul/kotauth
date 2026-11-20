package com.kauth.adapter.web.api

import com.kauth.adapter.web.auth.MfaGate
import com.kauth.adapter.web.auth.MfaStep
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.EmailOtpService
import com.kauth.domain.service.MfaError
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OtpCodeResult
import com.kauth.domain.service.OtpError
import com.kauth.domain.service.OtpSendResult
import com.kauth.domain.service.OtpVerifyResult
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val CONSTANT_TIME_TARGET_MS = 800L
private const val PER_EMAIL_RATE_PREFIX = "otp_email"
private const val PER_IP_RATE_PREFIX = "otp_ip"
private const val MFA_TOKEN_PURPOSE = "otp-mfa"
private const val MFA_TOKEN_FIELDS = 5
private const val MFA_TOKEN_TTL_SECONDS = 300L
private const val TOTP_LENGTH = 6

/**
 * What the email-code API needs to ask for the second factor. Without [encryptionService] it
 * cannot hand out an MFA token, so a user who owes the second factor is refused, never let through.
 */
internal class OtpApiMfa(
    val gate: MfaGate,
    val mfaService: MfaService,
    val tenantRepository: TenantRepository,
    val encryptionService: EncryptionService?,
    val limiter: RateLimiterPort,
)

internal fun Route.apiOtpRoutes(
    emailOtpService: EmailOtpService,
    perEmailLimiter: RateLimiterPort,
    perIpLimiter: RateLimiterPort,
    mfa: OtpApiMfa,
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
            val ip = call.request.origin.remoteAddress

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
            val ip = call.request.origin.remoteAddress

            when (
                val result =
                    emailOtpService.verifyOtp(
                        tenantSlug = tenantSlug,
                        challengeId = body.challengeId,
                        code = body.otp,
                        ipAddress = ip,
                    )
            ) {
                is OtpVerifyResult.Success -> call.continueAfterEmailCode(tenantSlug, result, emailOtpService, mfa)
                is OtpVerifyResult.Failure -> call.respondOtpError(result.error)
            }
        }

        post("/verify-mfa") {
            requireScope(call, ApiScope.AUTH_VERIFY_OTP) ?: return@post
            val tenantSlug =
                call.parameters["tenantSlug"]
                    ?: return@post call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Missing tenant slug",
                        "tenantSlug path parameter is required.",
                    )
            call.verifySecondFactor(tenantSlug, call.receive<VerifyMfaRequest>(), emailOtpService, mfa)
        }
    }
}

private suspend fun ApplicationCall.verifySecondFactor(
    tenantSlug: String,
    body: VerifyMfaRequest,
    emailOtpService: EmailOtpService,
    mfa: OtpApiMfa,
) {
    val tenant = mfa.tenantRepository.findBySlug(tenantSlug)
    val token = tenant?.let { mfa.readToken(body.mfaToken, it.id) }
    if (token == null) {
        return respondProblem(
            HttpStatusCode.Unauthorized,
            "Invalid MFA token",
            "invalid_mfa_token: the token is unknown, expired or for another workspace.",
        )
    }
    if (!mfa.limiter.isAllowed("mfa:api:${tenant.id.value}:${token.userId.value}")) {
        return respondProblem(
            HttpStatusCode.TooManyRequests,
            "Rate limit exceeded",
            "Too many MFA attempts. Wait a few minutes before retrying.",
        )
    }
    val code = body.code.trim()
    val verified =
        if (code.length == TOTP_LENGTH && code.all { it.isDigit() }) {
            mfa.mfaService.verifyTotp(token.userId, code)
        } else {
            mfa.mfaService.verifyRecoveryCode(token.userId, code)
        }
    when (verified) {
        is MfaResult.Failure ->
            if (verified.error is MfaError.TotpLocked) {
                respondProblem(
                    HttpStatusCode.TooManyRequests,
                    "MFA locked",
                    "mfa_locked: too many failed codes. Try later or use a recovery code.",
                )
            } else {
                respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid code", "invalid_mfa_code")
            }
        is MfaResult.Success ->
            respondCode(
                emailOtpService.issueAuthorizationCode(tenantSlug, token.challengeId, token.userId),
            )
    }
}

private suspend fun ApplicationCall.continueAfterEmailCode(
    tenantSlug: String,
    verified: OtpVerifyResult.Success,
    emailOtpService: EmailOtpService,
    mfa: OtpApiMfa,
) {
    val tenant = mfa.tenantRepository.findBySlug(tenantSlug) ?: return respondOtpError(OtpError.TenantNotFound)
    when (mfa.gate.stepFor(verified.userId, tenant, verified.originatingClientId)) {
        MfaStep.PROCEED ->
            respondCode(emailOtpService.issueAuthorizationCode(tenantSlug, verified.challengeId, verified.userId))
        MfaStep.ENROLLMENT_REQUIRED ->
            respondProblem(
                HttpStatusCode.Forbidden,
                "MFA enrollment required",
                "mfa_enrollment_required: this workspace requires MFA and the user has not enrolled.",
            )
        MfaStep.CHALLENGE -> {
            val expiresAt = Instant.now().plusSeconds(MFA_TOKEN_TTL_SECONDS)
            val token =
                mfa.issueToken(tenant.id, verified.userId, verified.challengeId, expiresAt)
                    ?: return respondProblem(
                        HttpStatusCode.ServiceUnavailable,
                        "MFA unavailable",
                        "This server cannot complete the second factor over the API.",
                    )
            response.headers.append(HttpHeaders.ContentType, "application/problem+json")
            respond(
                HttpStatusCode.Forbidden,
                MfaRequiredProblem(
                    type = "https://kotauth.dev/errors/403",
                    title = "MFA required",
                    status = HttpStatusCode.Forbidden.value,
                    detail = "Send a TOTP or recovery code with this mfaToken to verify-mfa.",
                    error = "mfa_required",
                    mfaToken = token,
                    mfaRequirements = listOf("otp", "recovery-code"),
                    expiresAt = DateTimeFormatter.ISO_INSTANT.format(expiresAt),
                ),
            )
        }
    }
}

private suspend fun ApplicationCall.respondCode(result: OtpCodeResult) {
    when (result) {
        is OtpCodeResult.Success ->
            respond(
                HttpStatusCode.OK,
                VerifyOtpResponse(
                    authorizationCode = result.authorizationCode,
                    expiresAt = DateTimeFormatter.ISO_INSTANT.format(result.codeExpiresAt),
                    redirectUri = result.redirectUri,
                ),
            )
        is OtpCodeResult.Failure -> respondOtpError(result.error)
    }
}

private class MfaTokenClaims(
    val userId: UserId,
    val challengeId: String,
)

private fun OtpApiMfa.issueToken(
    tenantId: TenantId,
    userId: UserId,
    challengeId: String,
    expiresAt: Instant,
): String? =
    encryptionService?.signCookie(
        listOf(MFA_TOKEN_PURPOSE, tenantId.value, userId.value, challengeId, expiresAt.epochSecond).joinToString("|"),
    )

private fun OtpApiMfa.readToken(
    token: String,
    tenantId: TenantId,
): MfaTokenClaims? {
    val parts = encryptionService?.verifyCookie(token)?.split("|") ?: return null
    if (parts.size != MFA_TOKEN_FIELDS || parts[0] != MFA_TOKEN_PURPOSE) return null
    if (parts[1].toIntOrNull() != tenantId.value) return null
    val expiresAt = parts[4].toLongOrNull() ?: return null
    if (Instant.now().epochSecond > expiresAt) return null
    val userId = parts[2].toIntOrNull() ?: return null
    return MfaTokenClaims(UserId(userId), parts[3])
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
data class VerifyMfaRequest(
    val mfaToken: String,
    val code: String,
)

@Serializable
data class MfaRequiredProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val error: String,
    val mfaToken: String,
    val mfaRequirements: List<String>,
    val expiresAt: String,
)

@Serializable
data class VerifyOtpResponse(
    val authorizationCode: String,
    val expiresAt: String,
    val redirectUri: String,
)
