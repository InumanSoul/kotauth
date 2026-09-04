package com.kauth.adapter.web.auth

import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.AuthenticationFinishRequest
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.WebAuthnError
import com.kauth.domain.service.WebAuthnResult
import com.kauth.domain.service.WebAuthnService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.Base64

private const val AUTH_COOKIE = "KOTAUTH_PASSKEY_AUTH"
private const val CHALLENGE_TTL_SECONDS = 300
private const val PER_IP_RATE_PREFIX = "passkey_auth"

internal fun Route.passkeyAuthRoutes(
    webAuthnService: WebAuthnService,
    oauthService: OAuthService,
    encryptionService: EncryptionService,
    rateLimiter: RateLimiterPort,
    ssoTtlSeconds: Long,
    secure: Boolean,
) {
    post("/passkeys/authenticate/start") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@post call.respond(HttpStatusCode.NotFound)

        when (val result = webAuthnService.startAuthentication(tenant)) {
            is WebAuthnResult.Success -> {
                val optionsJson = result.value.publicKeyOptionsJson
                val encoded =
                    Base64
                        .getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(optionsJson.toByteArray(Charsets.UTF_8))
                val signed = encryptionService.signCookie(encoded)
                call.response.cookies.append(
                    Cookie(
                        name = AUTH_COOKIE,
                        value = signed,
                        maxAge = CHALLENGE_TTL_SECONDS,
                        path = "/t/$slug",
                        secure = secure,
                        httpOnly = true,
                        extensions = mapOf("SameSite" to "Strict"),
                    ),
                )
                call.respondText(optionsJson, ContentType.Application.Json)
            }
            is WebAuthnResult.Failure ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to result.error::class.simpleName),
                )
        }
    }

    post("/passkeys/authenticate/finish") {
        val ctx = call.attributes[AuthTenantAttr]
        val slug = ctx.slug
        val tenant = ctx.tenant ?: return@post call.respond(HttpStatusCode.NotFound)

        val ipAddress = call.request.origin.remoteAddress
        if (!rateLimiter.isAllowed("$PER_IP_RATE_PREFIX:$ipAddress")) {
            call.response.headers.append("Retry-After", "60")
            return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "RateLimited"))
        }

        val signed =
            call.request.cookies[AUTH_COOKIE]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidChallenge"))
        val encoded =
            encryptionService.verifyCookie(signed)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidChallenge"))
        val assertionRequestJson =
            String(
                Base64.getUrlDecoder().decode(encoded),
                Charsets.UTF_8,
            )

        val body = call.receive<JsonObject>()
        val credentialJson =
            body["credential"]?.toString()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MissingCredential"))

        when (
            val result =
                webAuthnService.finishAuthentication(
                    tenant,
                    assertionRequestJson,
                    AuthenticationFinishRequest(credentialJson),
                )
        ) {
            is WebAuthnResult.Success -> {
                call.response.cookies.append(
                    name = AUTH_COOKIE,
                    value = "",
                    maxAge = 0L,
                    path = "/t/$slug",
                    httpOnly = true,
                    secure = secure,
                )
                val oauthParams = call.getAuthContext(encryptionService) ?: AuthView.OAuthParams()
                if (oauthParams.isOAuthFlow) {
                    val redirectUrl =
                        call.buildAuthorizationCodeRedirectUrl(
                            slug = slug,
                            userId = result.value.userId,
                            tenantId = tenant.id,
                            oauthParams = oauthParams,
                            ipAddress = ipAddress,
                            authTime = Instant.now(),
                            mfaCompleted = result.value.userVerified,
                            ssoTtlSeconds = ssoTtlSeconds,
                            secure = secure,
                            oauthService = oauthService,
                            encryptionService = encryptionService,
                            renderError = { message ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to message))
                            },
                        ) ?: return@post
                    call.respond(HttpStatusCode.OK, mapOf("redirect_url" to redirectUrl))
                } else {
                    call.respond(
                        mapOf(
                            "message" to "Passkey authentication successful",
                            "user_id" to
                                result.value.userId.value
                                    .toString(),
                        ),
                    )
                }
            }
            is WebAuthnResult.Failure ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    buildMap {
                        put("error", result.error::class.simpleName ?: "Unknown")
                        (result.error as? WebAuthnError.VerificationFailed)?.reason?.let {
                            put("reason", it)
                        }
                    },
                )
        }
    }
}
