package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.MfaResult
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthResult
import com.kauth.domain.service.OAuthService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.mfaRoutes(
    oauthService: OAuthService,
    tenantRepository: TenantRepository,
    mfaService: MfaService?,
    encryptionService: EncryptionService,
) {
    get("/mfa-challenge") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val oauthParams = call.request.queryParameters.toOAuthParams()

        val rawPendingGet = call.request.cookies["KOTAUTH_MFA_PENDING"]
        if (rawPendingGet.isNullOrBlank() || encryptionService.verifyCookie(rawPendingGet) == null) {
            return@get call.respondRedirect("/t/$slug/login")
        }

        call.respondHtml(
            HttpStatusCode.OK,
            AuthView.mfaChallengePage(slug, theme, workspaceName, oauthParams = oauthParams),
        )
    }

    post("/mfa-challenge") {
        val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val tenant = tenantRepository.findBySlug(slug)
        val theme = tenant?.theme ?: TenantTheme.DEFAULT
        val workspaceName = tenant?.displayName ?: "KotAuth"
        val params = call.receiveParameters()
        val code = params["code"]?.trim() ?: ""
        val ipAddress = call.request.local.remoteAddress
        val oauthParams = params.toOAuthParams()

        val rawCookie = call.request.cookies["KOTAUTH_MFA_PENDING"]
        if (rawCookie.isNullOrBlank()) {
            return@post call.respondRedirect("/t/$slug/login")
        }
        val pending = encryptionService.verifyCookie(rawCookie)
        if (pending == null) {
            return@post call.respondRedirect("/t/$slug/login")
        }
        val parts = pending.split("|")
        if (parts.size != 3) {
            return@post call.respondRedirect("/t/$slug/login")
        }
        val userId = parts[0].toIntOrNull() ?: return@post call.respondRedirect("/t/$slug/login")
        val timestamp = parts[2].toLongOrNull() ?: return@post call.respondRedirect("/t/$slug/login")

        if (System.currentTimeMillis() - timestamp > 300_000) {
            return@post call.respondHtml(
                HttpStatusCode.Unauthorized,
                AuthView.mfaChallengePage(
                    slug,
                    theme,
                    workspaceName,
                    error = "MFA challenge expired. Please log in again.",
                    oauthParams = oauthParams,
                ),
            )
        }

        if (mfaService == null) {
            return@post call.respondRedirect("/t/$slug/login")
        }

        val mfaResult =
            if (code.length == 6 && code.all { it.isDigit() }) {
                mfaService.verifyTotp(userId, code)
            } else {
                mfaService.verifyRecoveryCode(userId, code)
            }

        when (mfaResult) {
            is MfaResult.Failure -> {
                call.respondHtml(
                    HttpStatusCode.Unauthorized,
                    AuthView.mfaChallengePage(
                        slug,
                        theme,
                        workspaceName,
                        error = "Invalid code. Please try again.",
                        oauthParams = oauthParams,
                    ),
                )
            }
            is MfaResult.Success -> {
                call.response.cookies.append(
                    name = "KOTAUTH_MFA_PENDING",
                    value = "",
                    maxAge = 0L,
                    path = "/t/$slug",
                    httpOnly = true,
                )

                if (oauthParams.isOAuthFlow) {
                    val clientId =
                        oauthParams.clientId
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing client_id")
                    val redirectUri =
                        oauthParams.redirectUri
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing redirect_uri")

                    when (
                        val codeResult =
                            oauthService.issueAuthorizationCode(
                                tenantSlug = slug,
                                userId = userId,
                                clientId = clientId,
                                redirectUri = redirectUri,
                                scopes = oauthParams.scope ?: "openid",
                                codeChallenge = oauthParams.codeChallenge,
                                codeChallengeMethod = oauthParams.codeChallengeMethod,
                                nonce = oauthParams.nonce,
                                state = oauthParams.state,
                                ipAddress = ipAddress,
                            )
                    ) {
                        is OAuthResult.Success -> {
                            val authCode = codeResult.value.code
                            val state = oauthParams.state
                            val redirect =
                                buildString {
                                    append(redirectUri)
                                    append("?code=").append(authCode)
                                    if (!state.isNullOrBlank()) append("&state=").append(state)
                                }
                            call.respondRedirect(redirect)
                        }
                        is OAuthResult.Failure -> {
                            call.respondHtml(
                                HttpStatusCode.BadRequest,
                                AuthView.mfaChallengePage(
                                    slug,
                                    theme,
                                    workspaceName,
                                    error = codeResult.error.toDescription(),
                                    oauthParams = oauthParams,
                                ),
                            )
                        }
                    }
                } else {
                    call.respond(
                        mapOf(
                            "message" to "MFA verification successful",
                            "user_id" to userId,
                        ),
                    )
                }
            }
        }
    }
}
