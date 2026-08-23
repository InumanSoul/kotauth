package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimErrorType
import com.kauth.domain.scim.ScimFailure
import io.ktor.http.HttpStatusCode
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
 * Renders a domain failure as the SCIM error envelope (RFC 7644 §3.12). `status` is a JSON
 * string per the spec, not a number — a numeric `status` is a wire-format violation some
 * clients reject outright.
 */
fun ScimFailure.toResponse(): Pair<HttpStatusCode, JsonObject> {
    val status = statusFor(type)
    val body =
        buildJsonObject {
            putJsonArray("schemas") { add(ERROR_SCHEMA) }
            put("scimType", type.name)
            put("detail", detail)
            put("status", status.value.toString())
        }
    return status to body
}
