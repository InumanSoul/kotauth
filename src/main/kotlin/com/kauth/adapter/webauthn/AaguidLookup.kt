package com.kauth.adapter.webauthn

import kotlinx.serialization.json.Json
import java.util.UUID

object AaguidLookup {
    private val map: Map<String, String> =
        run {
            val stream =
                javaClass.classLoader.getResourceAsStream("webauthn/aaguid-names.json")
                    ?: return@run emptyMap()
            Json.decodeFromString(stream.bufferedReader().readText())
        }

    fun displayName(aaguid: UUID?): String {
        if (aaguid == null) return "Unknown authenticator"
        return map[aaguid.toString()] ?: "Unknown authenticator"
    }
}
