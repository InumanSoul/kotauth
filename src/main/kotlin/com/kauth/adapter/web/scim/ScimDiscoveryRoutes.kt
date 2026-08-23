package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.ApiKeyAttr
import com.kauth.domain.model.ApiScope
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val LIST_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
private const val SERVICE_PROVIDER_CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"
private const val RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType"
private const val SCHEMA_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema"
private const val USER_SCHEMA_URN = "urn:ietf:params:scim:schemas:core:2.0:User"
private const val GROUP_SCHEMA_URN = "urn:ietf:params:scim:schemas:core:2.0:Group"

// Shared with the future query endpoint so the advertised cap and the enforced one can't drift.
internal const val SCIM_FILTER_MAX_RESULTS = 200

/**
 * Scope gate for the SCIM surface. Mirrors `requireScope` in `ApiHelpers.kt` — same check, same
 * attribute — but responds with the SCIM error envelope (RFC 7644 §3.12) instead of the REST
 * problem+json shape, because SCIM clients only parse the former. `requireScope` itself is left
 * untouched: the rest of the REST API depends on its response shape.
 */
internal suspend fun requireScimScope(call: ApplicationCall): Unit? {
    val key =
        call.attributes.getOrNull(ApiKeyAttr)
            ?: run {
                call.respondScimError(HttpStatusCode.Unauthorized, "A valid API key is required.")
                return null
            }
    if (ApiScope.SCIM !in key.scopes) {
        call.respondScimError(HttpStatusCode.Forbidden, "This API key does not have the 'scim' permission.")
        return null
    }
    return Unit
}

private suspend fun ApplicationCall.respondScimError(
    status: HttpStatusCode,
    detail: String,
) {
    val (_, body) = scimAuthError(status, detail)
    respondScim(status, body)
}

private suspend fun ApplicationCall.respondScim(
    status: HttpStatusCode,
    body: JsonObject,
) {
    response.headers.append(HttpHeaders.ContentType, "application/scim+json")
    respond(status, body)
}

// `/ServiceProviderConfig`, `/ResourceTypes`, `/Schemas` — RFC 7644 §4. Capabilities advertised
// below reflect only what's actually implemented (see ScimPatchEngine.kt, ScimFilter.kt).
fun Route.scimDiscoveryRoutes() {
    get("/ServiceProviderConfig") {
        requireScimScope(call) ?: return@get
        call.respondScim(HttpStatusCode.OK, SERVICE_PROVIDER_CONFIG)
    }

    get("/ResourceTypes") {
        requireScimScope(call) ?: return@get
        call.respondScim(HttpStatusCode.OK, RESOURCE_TYPES)
    }

    get("/Schemas") {
        requireScimScope(call) ?: return@get
        call.respondScim(HttpStatusCode.OK, SCHEMAS)
    }
}

private val SERVICE_PROVIDER_CONFIG: JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(SERVICE_PROVIDER_CONFIG_SCHEMA) }
        put("documentationUri", "https://www.rfc-editor.org/rfc/rfc7644")
        putJsonObject("patch") { put("supported", true) }
        putJsonObject("bulk") {
            put("supported", false)
            put("maxOperations", 0)
            put("maxPayloadSize", 0)
        }
        putJsonObject("filter") {
            put("supported", true)
            put("maxResults", SCIM_FILTER_MAX_RESULTS)
        }
        putJsonObject("changePassword") { put("supported", false) }
        putJsonObject("sort") { put("supported", false) }
        putJsonObject("etag") { put("supported", false) }
        putJsonArray("authenticationSchemes") {
            add(
                buildJsonObject {
                    put("type", "oauthbearertoken")
                    put("name", "API Key")
                    put(
                        "description",
                        "A KotAuth API key with the 'scim' scope, sent as a Bearer token: " +
                            "Authorization: Bearer kauth_...",
                    )
                    put("specUri", "https://www.rfc-editor.org/rfc/rfc6750")
                    put("primary", true)
                },
            )
        }
        putJsonObject("meta") { put("resourceType", "ServiceProviderConfig") }
    }

private val RESOURCE_TYPES: JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(LIST_RESPONSE_SCHEMA) }
        put("totalResults", 2)
        putJsonArray("Resources") {
            add(resourceType(id = "User", endpoint = "/Users", description = "User Account", schema = USER_SCHEMA_URN))
            // /Groups doesn't exist yet — listed anyway, deliberately, so this stays consistent with /Schemas.
            add(resourceType(id = "Group", endpoint = "/Groups", description = "Group", schema = GROUP_SCHEMA_URN))
        }
    }

private fun resourceType(
    id: String,
    endpoint: String,
    description: String,
    schema: String,
): JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(RESOURCE_TYPE_SCHEMA) }
        put("id", id)
        put("name", id)
        put("endpoint", endpoint)
        put("description", description)
        put("schema", schema)
        putJsonArray("schemaExtensions") {}
        putJsonObject("meta") {
            put("resourceType", "ResourceType")
            put("location", "/ResourceTypes/$id")
        }
    }

private val SCHEMAS: JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(LIST_RESPONSE_SCHEMA) }
        put("totalResults", 2)
        putJsonArray("Resources") {
            add(userSchema())
            add(groupSchema())
        }
    }

private fun userSchema(): JsonObject =
    schemaResource(
        id = USER_SCHEMA_URN,
        name = "User",
        description = "User Account",
        attributes =
            buildJsonArray {
                add(
                    attribute(
                        name = "userName",
                        type = "string",
                        required = true,
                        uniqueness = "server",
                        description = "Unique identifier for the user, used to log in.",
                    ),
                )
                add(
                    complexAttribute(
                        name = "name",
                        description = "The components of the user's name.",
                        subAttributes =
                            buildJsonArray {
                                add(attribute(name = "givenName", type = "string"))
                                add(attribute(name = "familyName", type = "string"))
                            },
                    ),
                )
                add(attribute(name = "displayName", type = "string", description = "The name displayed for the user."))
                add(
                    complexAttribute(
                        name = "emails",
                        multiValued = true,
                        description = "Email addresses for the user.",
                        subAttributes =
                            buildJsonArray {
                                add(attribute(name = "value", type = "string"))
                                add(attribute(name = "type", type = "string"))
                            },
                    ),
                )
                add(attribute(name = "active", type = "boolean", description = "Whether the user is enabled."))
                add(attribute(name = "externalId", type = "string", description = "Identifier assigned by the IdP."))
                add(
                    attribute(
                        name = "password",
                        type = "string",
                        mutability = "writeOnly",
                        returned = "never",
                        description = "The user's password. Write-only; never returned.",
                    ),
                )
                add(
                    complexAttribute(
                        name = "groups",
                        multiValued = true,
                        mutability = "readOnly",
                        description = "Groups the user belongs to.",
                        subAttributes =
                            buildJsonArray {
                                add(attribute(name = "value", type = "string", mutability = "readOnly"))
                                add(attribute(name = "display", type = "string", mutability = "readOnly"))
                            },
                    ),
                )
            },
    )

// No Group mapper exists yet either — this schema just keeps /Schemas honest with /ResourceTypes.
private fun groupSchema(): JsonObject =
    schemaResource(
        id = GROUP_SCHEMA_URN,
        name = "Group",
        description = "Group",
        attributes =
            buildJsonArray {
                add(
                    attribute(
                        name = "displayName",
                        type = "string",
                        required = true,
                        description = "The group's name.",
                    ),
                )
                add(
                    complexAttribute(
                        name = "members",
                        multiValued = true,
                        description = "Users belonging to the group.",
                        subAttributes =
                            buildJsonArray {
                                add(attribute(name = "value", type = "string"))
                                add(attribute(name = "display", type = "string"))
                                add(attribute(name = "type", type = "string"))
                            },
                    ),
                )
            },
    )

private fun schemaResource(
    id: String,
    name: String,
    description: String,
    attributes: JsonArray,
): JsonObject =
    buildJsonObject {
        putJsonArray("schemas") { add(SCHEMA_SCHEMA) }
        put("id", id)
        put("name", name)
        put("description", description)
        put("attributes", attributes)
        putJsonObject("meta") {
            put("resourceType", "Schema")
            put("location", "/Schemas/$id")
        }
    }

private fun attribute(
    name: String,
    type: String,
    multiValued: Boolean = false,
    required: Boolean = false,
    caseExact: Boolean = false,
    mutability: String = "readWrite",
    returned: String = "default",
    uniqueness: String = "none",
    description: String = "",
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("type", type)
        put("multiValued", multiValued)
        put("description", description)
        put("required", required)
        put("caseExact", caseExact)
        put("mutability", mutability)
        put("returned", returned)
        put("uniqueness", uniqueness)
    }

private fun complexAttribute(
    name: String,
    subAttributes: JsonArray,
    multiValued: Boolean = false,
    required: Boolean = false,
    mutability: String = "readWrite",
    description: String = "",
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("type", "complex")
        put("multiValued", multiValued)
        put("description", description)
        put("required", required)
        put("mutability", mutability)
        put("returned", "default")
        put("subAttributes", subAttributes)
    }
