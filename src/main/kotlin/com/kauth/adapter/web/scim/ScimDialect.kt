package com.kauth.adapter.web.scim

import com.kauth.adapter.web.api.ApiKeyAttr
import com.kauth.domain.model.ApiKey
import com.kauth.domain.scim.ScimPatchOp
import com.kauth.domain.scim.ScimResource
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonElement

/**
 * Normalises a provisioning client's wire format into the canonical SCIM types before the
 * domain sees it.
 *
 * The boundary lives here, in the adapter, so `domain/scim/` stays a strict RFC 7643/7644
 * implementation with no knowledge of which client sent the payload. A dialect may only
 * reshape the request body — it never reaches a repository, a service, or a response.
 */
interface ScimDialect {
    /** Stable identifier persisted on the API key; matched by [scimDialectFor]. */
    val id: String

    fun normalizeOps(body: JsonElement): Result<List<ScimPatchOp>>

    fun normalizeResource(body: JsonElement): Result<ScimResource>
}

/** The canonical dialect: a pass-through straight to [ScimJson], so it is behaviour-neutral. */
object RfcDialect : ScimDialect {
    override val id = ApiKey.DEFAULT_SCIM_DIALECT

    override fun normalizeOps(body: JsonElement): Result<List<ScimPatchOp>> = body.toScimPatchOps()

    override fun normalizeResource(body: JsonElement): Result<ScimResource> = body.toScimResource()
}

private val DIALECTS: Map<String, ScimDialect> = listOf(RfcDialect, EntraDialect).associateBy { it.id }

/**
 * Resolves a persisted dialect id. An unrecognised id falls back to [RfcDialect] rather than
 * failing the request: a key configured for a dialect this build no longer ships must keep
 * provisioning against the spec, not start rejecting every payload.
 */
fun scimDialectFor(id: String?): ScimDialect = DIALECTS[id] ?: RfcDialect

/**
 * The dialect configured on the API key authenticating this request. Selection is per key, so a
 * tenant can run a spec-compliant client and a quirky one side by side.
 */
internal fun ApplicationCall.scimDialect(): ScimDialect = scimDialectFor(attributes.getOrNull(ApiKeyAttr)?.scimDialect)
