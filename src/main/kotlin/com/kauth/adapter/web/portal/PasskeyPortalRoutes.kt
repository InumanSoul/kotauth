package com.kauth.adapter.web.portal

import com.kauth.domain.model.SessionId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.SessionRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import com.kauth.domain.service.RegistrationFinishRequest
import com.kauth.domain.service.WebAuthnError
import com.kauth.domain.service.WebAuthnResult
import com.kauth.domain.service.WebAuthnService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.json.*
import java.util.Base64

private const val REG_COOKIE = "KOTAUTH_PASSKEY_REG"
private const val CHALLENGE_TTL_SECONDS = 300

fun Route.passkeyPortalRoutes(
    webAuthnService: WebAuthnService,
    encryptionService: EncryptionService,
    tenantRepository: TenantRepository,
    userRepository: UserRepository,
    sessionRepository: SessionRepository? = null,
    secure: Boolean = false,
) {
    route("/t/{slug}/passkeys") {
        post("/register/start") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.resolvePortalSession(slug, sessionRepository)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val tenant =
                tenantRepository.findBySlug(slug)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
            val user =
                userRepository.findById(UserId(session.userId), TenantId(session.tenantId))
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            when (val result = webAuthnService.startRegistration(user, tenant)) {
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
                            name = REG_COOKIE,
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

        post("/register/finish") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.resolvePortalSession(slug, sessionRepository)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val tenant =
                tenantRepository.findBySlug(slug)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
            val user =
                userRepository.findById(UserId(session.userId), TenantId(session.tenantId))
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val signedCookie =
                call.request.cookies[REG_COOKIE]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidChallenge"))
            val encoded =
                encryptionService.verifyCookie(signedCookie)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidChallenge"))
            val creationOptionsJson =
                String(
                    Base64.getUrlDecoder().decode(encoded),
                    Charsets.UTF_8,
                )

            val body = call.receive<JsonObject>()
            val credentialJson =
                body["credential"]?.toString()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MissingCredential"))
            val name =
                body["name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.take(64) ?: "Passkey"

            when (
                val result =
                    webAuthnService.finishRegistration(
                        user,
                        tenant,
                        creationOptionsJson,
                        RegistrationFinishRequest(credentialJson, name),
                    )
            ) {
                is WebAuthnResult.Success -> {
                    call.response.cookies.append(
                        name = REG_COOKIE,
                        value = "",
                        maxAge = 0L,
                        path = "/t/$slug",
                        httpOnly = true,
                        secure = secure,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("id" to result.value.id.toString(), "name" to result.value.name),
                    )
                }
                is WebAuthnResult.Failure ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.error::class.simpleName),
                    )
            }
        }

        get {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val session =
                call.resolvePortalSession(slug, sessionRepository)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val credentials =
                webAuthnService.listForUser(
                    UserId(session.userId),
                    TenantId(session.tenantId),
                )
            call.respond(
                credentials.map {
                    buildJsonObject {
                        put("id", it.id.toString())
                        put("name", it.name)
                        put("created_at", it.createdAt.toString())
                        it.lastUsedAt?.let { ts -> put("last_used_at", ts.toString()) }
                        it.aaguid?.let { uid -> put("aaguid", uid.toString()) }
                    }
                },
            )
        }

        post("/{id}/rename") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.resolvePortalSession(slug, sessionRepository)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val id =
                call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidId"))
            val body = call.receive<JsonObject>()
            val newName =
                body["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "MissingName"))

            val result = webAuthnService.rename(UserId(session.userId), id, newName)
            when (result) {
                is WebAuthnResult.Success -> call.respond(HttpStatusCode.OK)
                is WebAuthnResult.Failure ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "CredentialNotFound"),
                    )
            }
        }

        post("/{id}/revoke") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session =
                call.resolvePortalSession(slug, sessionRepository)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))

            val id =
                call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "InvalidId"))
            val result = webAuthnService.revoke(UserId(session.userId), id, TenantId(session.tenantId))
            when (result) {
                is WebAuthnResult.Success -> call.respond(HttpStatusCode.NoContent)
                is WebAuthnResult.Failure ->
                    if (result.error is WebAuthnError.CannotRevokeLast) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    "Cannot revoke your last passkey on a passwordless tenant. " +
                                    "Enable password sign-in first, or add another passkey.",
                            ),
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "CredentialNotFound"),
                        )
                    }
            }
        }
    }
}

private fun ApplicationCall.resolvePortalSession(
    slug: String,
    sessionRepository: SessionRepository?,
): PortalSession? {
    val session = sessions.get<PortalSession>() ?: return null
    if (session.tenantSlug != slug) return null
    val dbSessionId = session.portalSessionId
    if (dbSessionId != null && sessionRepository != null) {
        val dbSession = sessionRepository.findById(SessionId(dbSessionId))
        if (dbSession == null || dbSession.revokedAt != null) {
            sessions.clear<PortalSession>()
            return null
        }
    }
    return session
}
