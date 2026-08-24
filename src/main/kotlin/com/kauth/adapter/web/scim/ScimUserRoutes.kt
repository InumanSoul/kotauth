package com.kauth.adapter.web.scim

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.adapter.web.api.TenantIdAttr
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.User
import com.kauth.domain.model.UserId
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.TransactionRunner
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimFilter
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
private const val PASSWORD_UPDATE_UNSUPPORTED =
    "password updates for existing users are not supported over SCIM; " +
        "use the admin API or a password-reset invite instead"

// Attributes with an existing indexed repository lookup: a single top-level `attr eq "..."`
// filter on one of these goes straight to that lookup instead of scanning the tenant.
private val SCIM_FAST_PATH_ATTRIBUTES = setOf("userName", "externalId", "id")

// Bounds how many User rows are materialised at once while scanning a tenant for a filter with
// no indexed lookup (compound filters, displayName, active); independent of how many match.
private const val SCIM_FILTER_SCAN_CHUNK_SIZE = 500

/** `/Users` — RFC 7644 §3.2-3.6. Every write goes through [AdminUserService], never the repository directly. */
fun Route.scimUserRoutes(
    adminUserService: AdminUserService,
    groupRepository: GroupRepository,
    transactionRunner: TransactionRunner,
) {
    get("/Users") {
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

        val resourceBase = "${call.resolvedBaseUrl()}${call.request.path()}"

        // Loads groups for exactly the page being returned, in one batched query — never for
        // the full match set. Callers must slice to the page before calling this.
        fun buildResources(page: List<User>): List<ScimResource> {
            if (page.isEmpty()) return emptyList()
            val groupsByUser = groupRepository.findGroupsForUsers(page.mapNotNull { it.id })
            return page.map { user ->
                ScimUserMapper.toResource(
                    user,
                    groups = groupsByUser[user.id] ?: emptyList(),
                    location = "$resourceBase/${user.id!!.value}",
                )
            }
        }

        if (filter == null) {
            // No filter: the database can page directly, so this request materialises only
            // one page of User rows instead of the whole tenant directory.
            val total = adminUserService.countUsers(tenantId)
            val page =
                if (count == 0) {
                    emptyList()
                } else {
                    adminUserService.listUsers(tenantId, limit = count, offset = startIndex - 1)
                }
            call.respondScim(
                HttpStatusCode.OK,
                listResponse(
                    total = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    startIndex = startIndex,
                    resources = buildResources(page),
                ),
            )
            return@get
        }

        // This grammar supports exactly one operator (eq) over seven attributes (see
        // ScimFilter) — not arbitrary RFC 7644 filters — so it is enumerable rather than
        // needing general filter-to-SQL translation. A single `attr eq "..."` on userName,
        // externalId, or id goes straight to that attribute's indexed repository lookup.
        // Everything else (compound filters, displayName, active) has no indexed lookup and
        // is evaluated by scanning the tenant in bounded chunks, so totalResults always
        // reflects a true full-tenant match count instead of a truncated sample.
        val matched: List<User> =
            filter.asFastPathEquality()?.let { (attr, literal) ->
                listOfNotNull(lookupFastPath(adminUserService, tenantId, attr, literal))
            } ?: scanForMatches(adminUserService, tenantId, filter)

        // count=0 is a directory-sizing probe: totalResults must reflect the match count with
        // no Resources returned, never the resources themselves.
        val page = if (count == 0) emptyList() else matched.drop(startIndex - 1).take(count)

        call.respondScim(
            HttpStatusCode.OK,
            listResponse(total = matched.size, startIndex = startIndex, resources = buildResources(page)),
        )
    }

    post("/Users") {
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
                        // Deferred past the transaction below — an SMTP round-trip must not
                        // hold the DB connection this create/enable-disable pair needs.
                        dispatchInvite = false,
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
                adminUserService.dispatchPendingInvite(id, tenantId, baseUrl)
                call.respondScimUser(
                    adminUserService,
                    groupRepository,
                    id,
                    tenantId,
                    HttpStatusCode.Created,
                    includeLocationHeader = true,
                )
            }
            is AdminResult.Failure -> call.respondAdminError(created.error)
        }
    }

    get("/Users/{id}") {
        val tenantId = call.attributes[TenantIdAttr]
        val userId = call.userIdParam() ?: return@get call.respondAdminError(AdminError.NotFound("User not found."))
        call.respondScimUser(adminUserService, groupRepository, userId, tenantId, HttpStatusCode.OK)
    }

    put("/Users/{id}") {
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
        call.rejectPasswordUpdate(write) ?: return@put
        call.applyScimWrite(adminUserService, groupRepository, transactionRunner, userId, tenantId, write)
    }

    patch("/Users/{id}") {
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
        call.rejectPasswordUpdate(write) ?: return@patch
        call.applyScimWrite(adminUserService, groupRepository, transactionRunner, userId, tenantId, write)
    }

    delete("/Users/{id}") {
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

/** A single top-level `attr eq literal` filter on a fast-path attribute, as (attr, literal text). */
private fun ScimFilter.asFastPathEquality(): Pair<String, String>? {
    val eq = this as? ScimFilter.Eq ?: return null
    if (eq.attr !in SCIM_FAST_PATH_ATTRIBUTES) return null
    val literal =
        when (val v = eq.value) {
            is ScimValue.Str -> v.value
            is ScimValue.Num -> v.value.toString()
            else -> return null
        }
    return eq.attr to literal
}

/**
 * Resolves a fast-path attribute/literal pair to its indexed repository lookup. The `id` case is
 * tenant-scoped by [AdminUserService.getUser] itself (its query filters by tenantId), so a global
 * id belonging to another tenant is structurally unreachable here — never surfaced as a 403 that
 * would confirm the row exists, just an absent result like any other non-match.
 */
private fun lookupFastPath(
    adminUserService: AdminUserService,
    tenantId: TenantId,
    attr: String,
    literal: String,
): User? =
    when (attr) {
        "userName" -> adminUserService.findByUsername(tenantId, literal)
        "externalId" -> adminUserService.findByExternalId(tenantId, literal)
        "id" ->
            literal.toIntOrNull()?.let { idValue ->
                (adminUserService.getUser(UserId(idValue), tenantId) as? AdminResult.Success)?.value
            }
        else -> null
    }

/**
 * Evaluates [filter] against every user in [tenantId], one bounded chunk at a time, for the
 * filter shapes with no indexed lookup (compound and/or, or a single eq on displayName/active).
 * Memory stays proportional to [SCIM_FILTER_SCAN_CHUNK_SIZE] rather than the tenant's directory
 * size, while [totalResults][listResponse] still reflects a true full-tenant match count.
 */
private fun scanForMatches(
    adminUserService: AdminUserService,
    tenantId: TenantId,
    filter: ScimFilter,
): List<User> {
    val matches = mutableListOf<User>()
    var offset = 0
    while (true) {
        val chunk = adminUserService.listUsers(tenantId, limit = SCIM_FILTER_SCAN_CHUNK_SIZE, offset = offset)
        chunk.forEach { user ->
            val attributes = ScimUserMapper.toResource(user).attributes
            if (filter.matches(ScimValue.Complex(attributes))) matches += user
        }
        if (chunk.size < SCIM_FILTER_SCAN_CHUNK_SIZE) break
        offset += SCIM_FILTER_SCAN_CHUNK_SIZE
    }
    return matches
}

/** Applies a resolved [ScimUserWrite] to an existing user and responds with the fresh resource. */
private suspend fun ApplicationCall.applyScimWrite(
    adminUserService: AdminUserService,
    groupRepository: GroupRepository,
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
        is AdminResult.Success ->
            respondScimUser(
                adminUserService,
                groupRepository,
                userId,
                tenantId,
                HttpStatusCode.OK,
            )
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
 * *existing* user through the tenant's password policy — only user creation has one. Silently
 * accepting and dropping it would be an undetectable divergence (the IdP believes a rotated
 * password took effect); rejecting fails loudly at the connector's next sync instead.
 */
private suspend fun ApplicationCall.rejectPasswordUpdate(write: ScimUserWrite): Unit? {
    if (write.plaintextPassword == null) return Unit
    val (status, body) = ScimFailure(ScimErrorType.invalidValue, PASSWORD_UPDATE_UNSUPPORTED).toResponse()
    respondScim(status, body)
    return null
}

private suspend fun ApplicationCall.respondScimUser(
    adminUserService: AdminUserService,
    groupRepository: GroupRepository,
    userId: UserId,
    tenantId: TenantId,
    status: HttpStatusCode,
    includeLocationHeader: Boolean = false,
) {
    when (val fresh = adminUserService.getUser(userId, tenantId)) {
        is AdminResult.Success -> {
            val location = userLocation(userId)
            if (includeLocationHeader) response.headers.append(HttpHeaders.Location, location)
            val groups = groupRepository.findGroupsForUser(userId)
            respondScim(status, ScimUserMapper.toResource(fresh.value, groups = groups, location = location).toJson())
        }
        is AdminResult.Failure -> respondAdminError(fresh.error)
    }
}

/**
 * Absolute URL for a single user resource, derived from the current request path. For
 * `/Users/{id}` routes (GET/PUT/PATCH) the path already ends in the id; for `/Users` routes
 * (POST) it doesn't, so the id is appended. Either way this lands on the same `.../Users/{id}`.
 */
private fun ApplicationCall.userLocation(id: UserId): String {
    val path = request.path()
    val base = if (parameters["id"] != null) path.substringBeforeLast("/") else path
    return "${resolvedBaseUrl()}$base/${id.value}"
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
