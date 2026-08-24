package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.service.AdminError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error"

private fun statusFor(type: ScimErrorType): HttpStatusCode =
    when (type) {
        ScimErrorType.uniqueness -> HttpStatusCode.Conflict
        ScimErrorType.invalidFilter,
        ScimErrorType.invalidPath,
        ScimErrorType.invalidValue,
        ScimErrorType.invalidSyntax,
        ScimErrorType.mutability,
        ScimErrorType.noTarget,
        -> HttpStatusCode.BadRequest
    }

/**
 * Builds the SCIM error envelope (RFC 7644 §3.12). `status` is a JSON string per the spec, not
 * a number — a numeric `status` is a wire-format violation some clients reject outright.
 * `scimType` is omitted when null: RFC 7644 only defines scimType values for the 400-series
 * conditions in [ScimErrorType] — it has no value for authentication or authorization failures.
 */
private fun errorEnvelope(
    status: HttpStatusCode,
    detail: String,
    scimType: String? = null,
): JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(ERROR_SCHEMA) }
        scimType?.let { put("scimType", it) }
        put("detail", detail)
        put("status", status.value.toString())
    }

/** Renders a domain failure as the SCIM error envelope (RFC 7644 §3.12). */
fun ScimFailure.toResponse(): Pair<HttpStatusCode, JsonObject> {
    val status = statusFor(type)
    return status to errorEnvelope(status, detail, type.name)
}

/**
 * Renders an authentication/authorization failure as the SCIM error envelope. Used for gates
 * that run before any [ScimErrorType]-shaped domain failure is possible — e.g. a missing or
 * under-scoped API key — so callers get the SCIM shape without inventing a fake scimType.
 */
fun scimAuthError(
    status: HttpStatusCode,
    detail: String,
): Pair<HttpStatusCode, JsonObject> = status to errorEnvelope(status, detail)

/**
 * Renders an [AdminError] from a domain service as the SCIM error envelope. Tenant-scoped
 * lookups already fold "belongs to another tenant" into "not found" (never 403).
 */
internal fun AdminError.toScimResponse(): Pair<HttpStatusCode, JsonObject> =
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

internal suspend fun ApplicationCall.respondAdminError(error: AdminError) {
    val (status, body) = error.toScimResponse()
    respondScim(status, body)
}
