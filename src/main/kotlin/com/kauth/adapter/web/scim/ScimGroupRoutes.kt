package com.kauth.adapter.web.scim

import com.kauth.adapter.web.admin.resolvedBaseUrl
import com.kauth.adapter.web.api.TenantIdAttr
import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.TenantId
import com.kauth.domain.port.GroupRepository
import com.kauth.domain.port.TransactionRunner
import com.kauth.domain.scim.MergedScimResource
import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimFilter
import com.kauth.domain.scim.ScimGroupMapper
import com.kauth.domain.scim.ScimGroupWrite
import com.kauth.domain.scim.ScimPatchEngine
import com.kauth.domain.scim.ScimResource
import com.kauth.domain.scim.ScimValue
import com.kauth.domain.scim.parseFilter
import com.kauth.domain.service.ScimGroupMembershipService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.PayloadTooLargeException
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

// Only externalId has a genuine tenant-unique indexed lookup (see the partial unique index in
// V60). Group name is unique only per (tenant, parent) — not a safe single-group fast path — so
// displayName, like Users' own displayName filter, falls back to the bounded scan below.
private val SCIM_GROUP_FAST_PATH_ATTRIBUTES = setOf("externalId")

// Bounds how many Group rows are materialised at once while scanning a tenant for a displayName
// filter, independent of how many groups the tenant has.
private const val SCIM_GROUP_FILTER_SCAN_CHUNK_SIZE = 500

/**
 * `/Groups` — RFC 7644 §3.2-3.6. Every group lookup by id is resolved within [tenantId] first
 * ([resolveTenantGroup]) — [ScimGroupMembershipService.reconcile] validates member ids against
 * the tenant but deliberately not the group id itself, so an unscoped `findById` here would let
 * one workspace's SCIM key write membership into another workspace's group.
 */
fun Route.scimGroupRoutes(
    groupRepository: GroupRepository,
    scimGroupMembershipService: ScimGroupMembershipService,
    transactionRunner: TransactionRunner,
) {
    get("/Groups") {
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

        if (filter == null) {
            val total = groupRepository.countByTenantId(tenantId)
            val page =
                if (count == 0) {
                    emptyList()
                } else {
                    groupRepository.findByTenantIdWithoutRoles(tenantId, limit = count, offset = startIndex - 1)
                }
            call.respondScim(
                HttpStatusCode.OK,
                groupListResponse(
                    groupRepository = groupRepository,
                    total = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    startIndex = startIndex,
                    page = page,
                    resourceBase = resourceBase,
                ),
            )
            return@get
        }

        // This grammar supports exactly one operator (eq) — see ScimFilter — so a single
        // `externalId eq "..."` goes straight to its indexed lookup; displayName and any compound
        // filter have no safe single-group indexed lookup and are evaluated by scanning the
        // tenant in bounded chunks, so totalResults always reflects a true full-tenant match count.
        val matched: List<Group> =
            filter.asGroupFastPathEquality()?.let { (attr, literal) ->
                listOfNotNull(lookupGroupFastPath(groupRepository, tenantId, attr, literal))
            } ?: scanForGroupMatches(groupRepository, tenantId, filter)

        val page = if (count == 0) emptyList() else matched.drop(startIndex - 1).take(count)

        call.respondScim(
            HttpStatusCode.OK,
            groupListResponse(
                groupRepository,
                total = matched.size,
                startIndex = startIndex,
                page = page,
                resourceBase = resourceBase,
            ),
        )
    }

    post("/Groups") {
        val tenantId = call.attributes[TenantIdAttr]

        val resource = call.receiveScimResource() ?: return@post
        // POST creates a resource from scratch: the body is the complete initial state, not a
        // partial patch, so it is wrapped directly rather than going through a merge step.
        val write =
            ScimGroupMapper
                .toDomain(
                    MergedScimResource.fromFullReplace(resource),
                    existing = null,
                    tenantId,
                ).getOrElse {
                    call.respondScimFailure(it)
                    return@post
                }

        call.rejectGroupConflict(groupRepository, tenantId, write.group, excludeId = null) ?: return@post

        val result =
            runScimGroupTransaction(transactionRunner) {
                val saved = groupRepository.save(write.group)
                val reconciled = scimGroupMembershipService.reconcile(saved.id!!, tenantId, write.memberIds)
                if (reconciled.isFailure) {
                    throw ScimGroupWriteRollback(reconciled.exceptionOrNull() as ScimFailure)
                }
                saved
            }

        when {
            result.isSuccess -> {
                val saved = result.getOrThrow()
                call.respondScimGroup(
                    groupRepository,
                    saved.id!!,
                    tenantId,
                    HttpStatusCode.Created,
                    includeLocationHeader = true,
                )
            }
            else -> call.respondScimFailure(result.exceptionOrNull()!!)
        }
    }

    get("/Groups/{id}") {
        val tenantId = call.attributes[TenantIdAttr]
        val groupId =
            call.groupIdParam() ?: return@get call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
        call.respondScimGroup(groupRepository, groupId, tenantId, HttpStatusCode.OK)
    }

    put("/Groups/{id}") {
        val tenantId = call.attributes[TenantIdAttr]
        val groupId =
            call.groupIdParam() ?: return@put call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
        val existing = resolveTenantGroup(groupRepository, groupId, tenantId)
        if (existing == null) {
            call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
            return@put
        }

        val resource = call.receiveScimResource() ?: return@put
        // PUT is a full replace: the raw body IS the desired end state, so it is wrapped directly
        // rather than going through the merge-first path PATCH requires.
        val write =
            ScimGroupMapper
                .toDomain(
                    MergedScimResource.fromFullReplace(resource),
                    existing = existing,
                    tenantId,
                ).getOrElse {
                    call.respondScimFailure(it)
                    return@put
                }

        call.rejectGroupConflict(groupRepository, tenantId, write.group, excludeId = existing.id) ?: return@put
        call.applyScimGroupWrite(
            groupRepository,
            scimGroupMembershipService,
            transactionRunner,
            groupId,
            tenantId,
            write,
        )
    }

    patch("/Groups/{id}") {
        val tenantId = call.attributes[TenantIdAttr]
        val groupId =
            call.groupIdParam() ?: return@patch call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
        val existing = resolveTenantGroup(groupRepository, groupId, tenantId)
        if (existing == null) {
            call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
            return@patch
        }

        val body = call.receiveJsonElementOrRespondError() ?: return@patch
        val ops =
            body.toScimPatchOps().getOrElse {
                call.respondScimFailure(it)
                return@patch
            }

        // Merge before mapping: build the current resource (including current membership), apply
        // the ops to a copy of it, and only then map to the domain — see ScimGroupMapper.toDomain's
        // KDoc. applyMerged (not apply) is required: it is the only way to produce a
        // MergedScimResource for PATCH.
        val currentMembers = groupRepository.findUserIdsInGroup(groupId)
        val currentResource = ScimGroupMapper.toResource(existing, currentMembers)
        val merged =
            ScimPatchEngine().applyMerged(currentResource, ops).getOrElse {
                call.respondScimFailure(it)
                return@patch
            }
        val write =
            ScimGroupMapper.toDomain(merged, existing = existing, tenantId).getOrElse {
                call.respondScimFailure(it)
                return@patch
            }

        call.rejectGroupConflict(groupRepository, tenantId, write.group, excludeId = existing.id) ?: return@patch
        call.applyScimGroupWrite(
            groupRepository,
            scimGroupMembershipService,
            transactionRunner,
            groupId,
            tenantId,
            write,
        )
    }

    delete("/Groups/{id}") {
        val tenantId = call.attributes[TenantIdAttr]
        val groupId =
            call.groupIdParam() ?: return@delete call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
        val existing = resolveTenantGroup(groupRepository, groupId, tenantId)
        if (existing == null) {
            call.respondScimError(HttpStatusCode.NotFound, "Group not found.")
            return@delete
        }
        // groups.parent_group_id cascades (ON DELETE CASCADE, V12): deleting a group with
        // children would silently remove every descendant group along with their memberships
        // and role assignments. Refuse rather than let an IdP nuke subgroups it never targeted;
        // it can delete them explicitly if it means to. Member users are untouched either way —
        // user_groups also cascades, but a user row itself is never deleted by this.
        if (groupRepository.findChildren(groupId).isNotEmpty()) {
            call.respondScimFailure(
                ScimFailure(
                    ScimErrorType.uniqueness,
                    "group has child groups; delete or reparent them before deleting this group",
                ),
            )
            return@delete
        }
        groupRepository.delete(groupId)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Persists metadata via [GroupRepository.update] and membership via [ScimGroupMembershipService.reconcile]. */
private suspend fun ApplicationCall.applyScimGroupWrite(
    groupRepository: GroupRepository,
    scimGroupMembershipService: ScimGroupMembershipService,
    transactionRunner: TransactionRunner,
    groupId: GroupId,
    tenantId: TenantId,
    write: ScimGroupWrite,
) {
    val result =
        runScimGroupTransaction(transactionRunner) {
            val saved = groupRepository.update(write.group)
            val reconciled = scimGroupMembershipService.reconcile(groupId, tenantId, write.memberIds)
            if (reconciled.isFailure) {
                throw ScimGroupWriteRollback(reconciled.exceptionOrNull() as ScimFailure)
            }
            saved
        }

    when {
        result.isSuccess -> respondScimGroup(groupRepository, groupId, tenantId, HttpStatusCode.OK)
        else -> respondScimFailure(result.exceptionOrNull()!!)
    }
}

/**
 * Resolves a group by id scoped to [tenantId]. [ScimGroupMembershipService.reconcile] validates
 * only the *members* it is handed, not the group id itself — every route above must call this
 * before touching the group so a cross-tenant id folds into "not found" (404), never a 403 that
 * would confirm the group exists in another workspace.
 */
private fun resolveTenantGroup(
    groupRepository: GroupRepository,
    groupId: GroupId,
    tenantId: TenantId,
): Group? = groupRepository.findById(groupId)?.takeIf { it.tenantId == tenantId }

private fun ApplicationCall.groupIdParam(): GroupId? = parameters["id"]?.toIntOrNull()?.let { GroupId(it) }

private suspend fun ApplicationCall.respondScimGroup(
    groupRepository: GroupRepository,
    groupId: GroupId,
    tenantId: TenantId,
    status: HttpStatusCode,
    includeLocationHeader: Boolean = false,
) {
    val group = resolveTenantGroup(groupRepository, groupId, tenantId)
    if (group == null) {
        respondScimError(HttpStatusCode.NotFound, "Group not found.")
        return
    }
    val location = groupLocation(groupId)
    if (includeLocationHeader) response.headers.append(HttpHeaders.Location, location)
    val members = groupRepository.findUserIdsInGroup(groupId)
    respondScim(status, ScimGroupMapper.toResource(group, members, location = location).toJson())
}

/**
 * Absolute URL for a single group resource, derived from the current request path. For
 * `/Groups/{id}` routes it already ends in the id; for `/Groups` (POST) it doesn't, so the id is
 * appended. Either way this lands on the same `.../Groups/{id}`.
 */
private fun ApplicationCall.groupLocation(id: GroupId): String {
    val path = request.path()
    val base = if (parameters["id"] != null) path.substringBeforeLast("/") else path
    return "${resolvedBaseUrl()}$base/${id.value}"
}

/**
 * `displayName` is only unique per (tenant, parent); `externalId` is unique per tenant (V60's
 * partial index). A conflicting write would otherwise surface as a raw database constraint
 * violation instead of a SCIM `uniqueness` error.
 */
private suspend fun ApplicationCall.rejectGroupConflict(
    groupRepository: GroupRepository,
    tenantId: TenantId,
    candidate: Group,
    excludeId: GroupId?,
): Unit? {
    val nameConflict = groupRepository.findByName(tenantId, candidate.name, candidate.parentGroupId)
    if (nameConflict != null && nameConflict.id != excludeId) {
        respondScimFailure(ScimFailure(ScimErrorType.uniqueness, "a group named '${candidate.name}' already exists"))
        return null
    }
    val externalId = candidate.externalId
    if (externalId != null) {
        val extConflict = groupRepository.findByExternalId(tenantId, externalId)
        if (extConflict != null && extConflict.id != excludeId) {
            respondScimFailure(ScimFailure(ScimErrorType.uniqueness, "externalId '$externalId' is already in use"))
            return null
        }
    }
    return Unit
}

/** A single top-level `attr eq literal` filter on a fast-path attribute, as (attr, literal text). */
private fun ScimFilter.asGroupFastPathEquality(): Pair<String, String>? {
    val eq = this as? ScimFilter.Eq ?: return null
    if (eq.attr !in SCIM_GROUP_FAST_PATH_ATTRIBUTES) return null
    val literal =
        when (val v = eq.value) {
            is ScimValue.Str -> v.value
            is ScimValue.Num -> v.value.toString()
            else -> return null
        }
    return eq.attr to literal
}

/** Tenant-scoped, indexed: the only fast-path attribute for Groups is externalId (see above). */
private fun lookupGroupFastPath(
    groupRepository: GroupRepository,
    tenantId: TenantId,
    attr: String,
    literal: String,
): Group? =
    when (attr) {
        "externalId" -> groupRepository.findByExternalId(tenantId, literal)
        else -> null
    }

/**
 * Evaluates [filter] against every group in [tenantId], one bounded chunk at a time. Memory stays
 * proportional to [SCIM_GROUP_FILTER_SCAN_CHUNK_SIZE] rather than the tenant's group count, while
 * totalResults still reflects a true full-tenant match count. Membership is never loaded here —
 * the supported filter attributes (displayName, externalId) don't need it.
 */
private fun scanForGroupMatches(
    groupRepository: GroupRepository,
    tenantId: TenantId,
    filter: ScimFilter,
): List<Group> {
    val matches = mutableListOf<Group>()
    var offset = 0
    while (true) {
        val chunk =
            groupRepository.findByTenantIdWithoutRoles(
                tenantId,
                limit = SCIM_GROUP_FILTER_SCAN_CHUNK_SIZE,
                offset = offset,
            )
        chunk.forEach { group ->
            val attributes = ScimGroupMapper.toResource(group, emptyList()).attributes
            if (filter.matches(ScimValue.Complex(attributes))) matches += group
        }
        if (chunk.size < SCIM_GROUP_FILTER_SCAN_CHUNK_SIZE) break
        offset += SCIM_GROUP_FILTER_SCAN_CHUNK_SIZE
    }
    return matches
}

/** Loads membership for exactly [page] in one batched query — never for the full match set. */
private fun groupListResponse(
    groupRepository: GroupRepository,
    total: Int,
    startIndex: Int,
    page: List<Group>,
    resourceBase: String,
): JsonObject {
    val membersByGroup =
        if (page.isEmpty()) emptyMap() else groupRepository.findUserIdsForGroups(page.mapNotNull { it.id })
    val resources =
        page.map { group ->
            ScimGroupMapper.toResource(
                group,
                membersByGroup[group.id] ?: emptyList(),
                location = "$resourceBase/${group.id!!.value}",
            )
        }
    return buildJsonObject {
        putJsonArray("schemas") { add(LIST_RESPONSE_SCHEMA) }
        put("totalResults", total)
        put("startIndex", startIndex)
        put("itemsPerPage", resources.size)
        putJsonArray("Resources") { resources.forEach { add(it.toJson()) } }
    }
}

/**
 * Internal-only: thrown when the membership-reconcile leg of a write fails, so the metadata
 * write rolls back with it instead of committing a group with the wrong membership. Never
 * crosses the route boundary — [runScimGroupTransaction] always catches it.
 */
private class ScimGroupWriteRollback(
    val failure: ScimFailure,
) : RuntimeException()

private fun <T> runScimGroupTransaction(
    transactionRunner: TransactionRunner,
    block: () -> T,
): Result<T> =
    try {
        Result.success(transactionRunner.runInTransaction(block))
    } catch (e: ScimGroupWriteRollback) {
        Result.failure(e.failure)
    }

// Body parsing can throw several different exception types across content negotiation and the
// JSON parser; all of them mean the same thing here: a malformed request. PayloadTooLargeException
// is a distinct condition (body too big, not malformed) and must reach StatusPages so SCIM clients
// get 413 with a scimType, not a 400 they'll treat as permanent and drop the record for.
private suspend fun ApplicationCall.receiveJsonElementOrRespondError(): JsonElement? =
    try {
        receive<JsonElement>()
    } catch (e: PayloadTooLargeException) {
        throw e
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
