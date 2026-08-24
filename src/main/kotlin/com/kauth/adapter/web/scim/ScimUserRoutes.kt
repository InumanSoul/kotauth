package com.kauth.adapter.web.scim

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.adapter.web.api.TenantIdAttr
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.TransactionRunner
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimPatchEngine
import com.kauth.domain.scim.ScimResource
import com.kauth.domain.scim.ScimUserMapper
import com.kauth.domain.scim.ScimUserWrite
import com.kauth.domain.scim.ScimValue
import com.kauth.domain.scim.parseFilter
import com.kauth.domain.service.AdminError
import com.kauth.domain.service.AdminResult
import com.kauth.domain.service.AdminUserService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val LIST_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse"

/** `/Users` — RFC 7644 §3.2-3.6. Every write goes through [AdminUserService], never the repository directly. */
fun Route.scimUserRoutes(
    adminUserService: AdminUserService,
    transactionRunner: TransactionRunner,
) {
    get("/Users") {
        requireScimScope(call) ?: return@get
        val tenantId = call.attributes[TenantIdAttr]

        val filter =
            call.request.queryParameters["filter"]?.let { raw ->
                parseFilter(raw).getOrElse {
                    call.respondScimFailure(it)
                    return@get
                }
            }

        val startIndex = (call.request.queryParameters["startIndex"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count =
            (call.request.queryParameters["count"]?.toIntOrNull() ?: SCIM_FILTER_MAX_RESULTS)
                .coerceIn(0, SCIM_FILTER_MAX_RESULTS)

        val resources = adminUserService.listUsers(tenantId).map { ScimUserMapper.toResource(it) }
        val matched =
            filter?.let { f -> resources.filter { r -> f.matches(ScimValue.Complex(r.attributes)) } } ?: resources
        // count=0 is a directory-sizing probe: totalResults must reflect the match count with
        // no Resources returned, never the resources themselves.
        val page = if (count == 0) emptyList() else matched.drop(startIndex - 1).take(count)

        call.respondScim(
            HttpStatusCode.OK,
            listResponse(total = matched.size, startIndex = startIndex, resources = page),
        )
    }

    post("/Users") {
        requireScimScope(call) ?: return@post
        val tenantId = call.attributes[TenantIdAttr]

        val resource = call.receiveScimResource() ?: return@post
        val write =
            ScimUserMapper.toDomain(resource, existing = null, tenantId).getOrElse {
                call.respondScimFailure(it)
                return@post
            }

        val baseUrl = call.resolvedBaseUrl()
        val created =
            runScimTransaction(transactionRunner) {
                val result =
                    adminUserService.createUser(
                        tenantId = tenantId,
                        username = write.user.username,
                        email = write.user.email,
                        fullName = write.user.fullName,
                        password = write.plaintextPassword,
                        sendInvite = write.plaintextPassword == null,
                        baseUrl = baseUrl,
                        externalId = write.user.externalId,
                        givenName = write.user.givenName,
                        familyName = write.user.familyName,
                    )
                if (result is AdminResult.Success && !write.user.enabled) {
                    val disabled = adminUserService.setUserEnabled(result.value.id!!, tenantId, false)
                    if (disabled is AdminResult.Failure) throw ScimWriteRollback(disabled.error)
                }
                result
            }

        when (created) {
            is AdminResult.Success -> {
                val id = created.value.id!!
                val location = "$baseUrl${call.request.path()}/${id.value}"
                call.respondScimUser(adminUserService, id, tenantId, HttpStatusCode.Created, location)
            }
            is AdminResult.Failure -> call.respondAdminError(created.error)
        }
    }

    get("/Users/{id}") {
        requireScimScope(call) ?: return@get
        val tenantId = call.attributes[TenantIdAttr]
        val userId = call.userIdParam() ?: return@get call.respondAdminError(AdminError.NotFound("User not found."))
        when (val result = adminUserService.getUser(userId, tenantId)) {
            is AdminResult.Success ->
                call.respondScim(
                    HttpStatusCode.OK,
                    ScimUserMapper.toResource(result.value).toJson(),
                )
            is AdminResult.Failure -> call.respondAdminError(result.error)
        }
    }

    put("/Users/{id}") {
        requireScimScope(call) ?: return@put
        val tenantId = call.attributes[TenantIdAttr]
        val userId = call.userIdParam() ?: return@put call.respondAdminError(AdminError.NotFound("User not found."))
        val existing = adminUserService.getUser(userId, tenantId)
        if (existing !is AdminResult.Success) {
            call.respondAdminError((existing as AdminResult.Failure).error)
            return@put
        }

        val resource = call.receiveScimResource() ?: return@put
        // PUT is a full replace: the raw body IS the desired end state, so it goes to toDomain
        // directly rather than through the merge-first path PATCH requires.
        val write =
            ScimUserMapper.toDomain(resource, existing = existing.value, tenantId).getOrElse {
                call.respondScimFailure(it)
                return@put
            }
        call.rejectUsernameRename(existing.value.username, write) ?: return@put
        call.warnIfPasswordUnsupported(userId, write)
        call.applyScimWrite(adminUserService, transactionRunner, userId, tenantId, write)
    }

    patch("/Users/{id}") {
        requireScimScope(call) ?: return@patch
        val tenantId = call.attributes[TenantIdAttr]
        val userId = call.userIdParam() ?: return@patch call.respondAdminError(AdminError.NotFound("User not found."))
        val existing = adminUserService.getUser(userId, tenantId)
        if (existing !is AdminResult.Success) {
            call.respondAdminError((existing as AdminResult.Failure).error)
            return@patch
        }

        val body = call.receiveJsonElementOrRespondError() ?: return@patch
        val ops =
            body.toScimPatchOps().getOrElse {
                call.respondScimFailure(it)
                return@patch
            }

        // Merge before mapping: build the current resource, apply the ops to a copy of it, and
        // only then map to the domain. Mapping a bare patch body would treat every attribute the
        // caller didn't mention as cleared — see ScimUserMapper.toDomain's KDoc.
        val currentResource = ScimUserMapper.toResource(existing.value)
        val merged =
            ScimPatchEngine().apply(currentResource, ops).getOrElse {
                call.respondScimFailure(it)
                return@patch
            }
        val write =
            ScimUserMapper.toDomain(merged, existing = existing.value, tenantId).getOrElse {
                call.respondScimFailure(it)
                return@patch
            }
        call.rejectUsernameRename(existing.value.username, write) ?: return@patch
        call.warnIfPasswordUnsupported(userId, write)
        call.applyScimWrite(adminUserService, transactionRunner, userId, tenantId, write)
    }

    delete("/Users/{id}") {
        requireScimScope(call) ?: return@delete
        val tenantId = call.attributes[TenantIdAttr]
        val userId = call.userIdParam() ?: return@delete call.respondAdminError(AdminError.NotFound("User not found."))
        // Deactivates rather than deletes: the account stays fetchable, audited like any other
        // admin disable — never a raw repository delete.
        when (val result = adminUserService.setUserEnabled(userId, tenantId, false)) {
            is AdminResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AdminResult.Failure -> call.respondAdminError(result.error)
        }
    }
}

/** Applies a resolved [ScimUserWrite] to an existing user and responds with the fresh resource. */
private suspend fun ApplicationCall.applyScimWrite(
    adminUserService: AdminUserService,
    transactionRunner: TransactionRunner,
    userId: UserId,
    tenantId: TenantId,
    write: ScimUserWrite,
) {
    val result =
        runScimTransaction(transactionRunner) {
            val profile =
                adminUserService.replaceUserProfile(
                    userId = userId,
                    tenantId = tenantId,
                    email = write.user.email,
                    fullName = write.user.fullName,
                    externalId = write.user.externalId,
                    givenName = write.user.givenName,
                    familyName = write.user.familyName,
                )
            if (profile is AdminResult.Success && profile.value.enabled != write.user.enabled) {
                val disabled = adminUserService.setUserEnabled(userId, tenantId, write.user.enabled)
                if (disabled is AdminResult.Failure) throw ScimWriteRollback(disabled.error)
            }
            profile
        }

    when (result) {
        is AdminResult.Success -> respondScimUser(adminUserService, userId, tenantId, HttpStatusCode.OK)
        is AdminResult.Failure -> respondAdminError(result.error)
    }
}

/**
 * Internal-only: thrown when a combined write's second leg (e.g. the enable/disable toggle
 * following a create or profile replace) fails, so [TransactionRunner.runInTransaction] rolls
 * back the whole write instead of committing a half-applied one. Never crosses the route boundary
 * — [runScimTransaction] always catches it and converts it back to an [AdminResult.Failure].
 */
private class ScimWriteRollback(
    val error: AdminError,
) : RuntimeException()

/** Runs [block] in a transaction, folding a [ScimWriteRollback] back into a typed failure. */
private fun <T> runScimTransaction(
    transactionRunner: TransactionRunner,
    block: () -> AdminResult<T>,
): AdminResult<T> =
    try {
        transactionRunner.runInTransaction(block)
    } catch (e: ScimWriteRollback) {
        AdminResult.Failure(e.error)
    }

/** `userName` is the SCIM correlation key; a PUT/PATCH that tries to change it is rejected, never silently dropped. */
private suspend fun ApplicationCall.rejectUsernameRename(
    existingUsername: String,
    write: ScimUserWrite,
): Unit? {
    if (write.user.username == existingUsername) return Unit
    val (status, body) =
        ScimFailure(
            ScimErrorType.mutability,
            "userName cannot be changed once assigned; it is the SCIM correlation key.",
        ).toResponse()
    respondScim(status, body)
    return null
}

/**
 * AdminUserService has no method that applies an admin-supplied plaintext password to an
 * *existing* user through the tenant's password policy — only user creation has one. Rather than
 * hash it here or write it straight into passwordHash, the supplied value is dropped and the
 * existing hash is left untouched; this is logged (never the password itself) so the gap is visible.
 */
private fun ApplicationCall.warnIfPasswordUnsupported(
    userId: UserId,
    write: ScimUserWrite,
) {
    if (write.plaintextPassword != null) {
        application.log.warn(
            "SCIM update for user {} included a password; updating an existing user's password " +
                "via SCIM is not supported yet, so it was ignored.",
            userId.value,
        )
    }
}

private suspend fun ApplicationCall.respondScimUser(
    adminUserService: AdminUserService,
    userId: UserId,
    tenantId: TenantId,
    status: HttpStatusCode,
    location: String? = null,
) {
    when (val fresh = adminUserService.getUser(userId, tenantId)) {
        is AdminResult.Success -> {
            location?.let { response.headers.append(HttpHeaders.Location, it) }
            respondScim(status, ScimUserMapper.toResource(fresh.value).toJson())
        }
        is AdminResult.Failure -> respondAdminError(fresh.error)
    }
}

private fun ApplicationCall.userIdParam(): UserId? = parameters["id"]?.toIntOrNull()?.let { UserId(it) }

private suspend fun ApplicationCall.respondAdminError(error: AdminError) {
    val (status, body) = error.toScimResponse()
    respondScim(status, body)
}

/** Tenant-scoped lookups already fold "belongs to another tenant" into "not found" (never 403). */
private fun AdminError.toScimResponse(): Pair<HttpStatusCode, JsonObject> =
    when (this) {
        is AdminError.NotFound -> scimAuthError(HttpStatusCode.NotFound, message)
        is AdminError.Conflict -> ScimFailure(ScimErrorType.uniqueness, message).toResponse()
        is AdminError.Validation -> ScimFailure(ScimErrorType.invalidValue, message).toResponse()
        AdminError.SmtpRequired, AdminError.NoMethodsEnabled ->
            ScimFailure(
                ScimErrorType.invalidValue,
                message,
            ).toResponse()
    }

// Body parsing can throw several different exception types across content negotiation and the
// JSON parser; all of them mean the same thing here: a malformed request.
private suspend fun ApplicationCall.receiveJsonElementOrRespondError(): JsonElement? =
    try {
        receive<JsonElement>()
    } catch (e: Exception) {
        respondScimError(HttpStatusCode.BadRequest, "Malformed JSON body: ${e.message}")
        null
    }

private suspend fun ApplicationCall.receiveScimResource(): ScimResource? {
    val element = receiveJsonElementOrRespondError() ?: return null
    return element.toScimResource().getOrElse {
        respondScimFailure(it)
        null
    }
}

private suspend fun ApplicationCall.respondScimFailure(e: Throwable) {
    val (status, body) =
        (e as? ScimFailure)?.toResponse()
            ?: scimAuthError(HttpStatusCode.BadRequest, e.message ?: "invalid request")
    respondScim(status, body)
}

private fun listResponse(
    total: Int,
    startIndex: Int,
    resources: List<ScimResource>,
): JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(LIST_RESPONSE_SCHEMA) }
        put("totalResults", total)
        put("startIndex", startIndex)
        put("itemsPerPage", resources.size)
        putJsonArray("Resources") { resources.forEach { add(it.toJson()) } }
    }
