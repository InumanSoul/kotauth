package com.kauth.adapter.web.scim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Anchors the resource lookup to this file's classloader; the loader itself is a top-level function. */
private object ScimFixtures

/**
 * Loads a hand-built fixture, dropping the `_`-prefixed provenance header so it can never
 * reach a dialect and influence normalisation.
 */
internal fun fixture(path: String): JsonElement {
    val raw =
        checkNotNull(ScimFixtures.javaClass.getResourceAsStream("/scim/fixtures/$path")) { "missing fixture: $path" }
            .use { it.readBytes().decodeToString() }
    return JsonObject(Json.parseToJsonElement(raw).jsonObject.filterKeys { !it.startsWith("_") })
}
