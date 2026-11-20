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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Base64

private const val AUTH_COOKIE = "KOTAUTH_PASSKEY_AUTH"
private const val CHALLENGE_TTL_SECONDS = 300
private const val PER_IP_RATE_PREFIX = "passkey_auth"

/**
 * Sign-in ceremonies one browser may have pending at once: the sign-in page open in more than
 * one tab, or its passkey autofill alongside the button. Each start adds a challenge; finish
 * uses the one the authenticator signed.
 */
private const val MAX_PENDING_CHALLENGES = 3

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
                val pending = call.pendingAssertionRequests(encryptionService) + optionsJson
                call.storePendingAssertionRequests(pending, slug, secure, encryptionService)
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

        val pending = call.pendingAssertionRequests(encryptionService)
        if (pending.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidChallenge"))
        }

        val body = call.receive<JsonObject>()
        val credential =
            body["credential"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MissingCredential"))
        val credentialJson = credential.toString()
        val signedChallenge = signedChallengeOf(credential)
        // An unmatched challenge still goes to verification, which refuses it and records the failure.
        val assertionRequestJson = pending.lastOrNull { challengeOf(it) == signedChallenge } ?: pending.last()

        when (
            val result =
                webAuthnService.finishAuthentication(
                    tenant,
                    assertionRequestJson,
                    AuthenticationFinishRequest(credentialJson),
                )
        ) {
            is WebAuthnResult.Success -> {
                call.storePendingAssertionRequests(pending - assertionRequestJson, slug, secure, encryptionService)
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

/**
 * The pending assertion requests carried by the signed challenge cookie, oldest first. A cookie
 * written before several could be pending holds a single request rather than a list.
 */
private fun ApplicationCall.pendingAssertionRequests(encryptionService: EncryptionService): List<String> {
    val encoded = request.cookies[AUTH_COOKIE]?.let { encryptionService.verifyCookie(it) } ?: return emptyList()
    val text = runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
    val parsed = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: return emptyList()
    return when (parsed) {
        is JsonArray -> parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonObject -> listOf(text)
        else -> emptyList()
    }
}

/** Writes the newest [MAX_PENDING_CHALLENGES] of [requests] back, or clears the cookie when none remain. */
private fun ApplicationCall.storePendingAssertionRequests(
    requests: List<String>,
    slug: String,
    secure: Boolean,
    encryptionService: EncryptionService,
) {
    val kept = requests.takeLast(MAX_PENDING_CHALLENGES)
    val value =
        if (kept.isEmpty()) {
            ""
        } else {
            val json = JsonArray(kept.map { JsonPrimitive(it) }).toString()
            encryptionService.signCookie(
                Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8)),
            )
        }
    response.cookies.append(
        Cookie(
            name = AUTH_COOKIE,
            value = value,
            maxAge = if (kept.isEmpty()) 0 else CHALLENGE_TTL_SECONDS,
            path = "/t/$slug",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

private fun challengeOf(assertionRequestJson: String): String? =
    runCatching {
        val root = Json.parseToJsonElement(assertionRequestJson).jsonObject
        val options = root["publicKey"]?.jsonObject ?: root
        options["challenge"]?.jsonPrimitive?.contentOrNull?.trimEnd('=')
    }.getOrNull()

/** The challenge inside the credential's clientDataJSON, which is what the authenticator signed. */
private fun signedChallengeOf(credential: JsonElement): String? =
    runCatching {
        val clientDataB64 =
            credential.jsonObject["response"]
                ?.jsonObject
                ?.get("clientDataJSON")
                ?.jsonPrimitive
                ?.content ?: return null
        val clientData = String(Base64.getUrlDecoder().decode(clientDataB64.trimEnd('=')), Charsets.UTF_8)
        Json
            .parseToJsonElement(clientData)
            .jsonObject["challenge"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trimEnd('=')
    }.getOrNull()
