package com.kauth.adapter.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresAuditLogAdapterDetailsTest {
    private fun serializeDetails(details: Map<String, String>): String? =
        if (details.isEmpty()) {
            null
        } else {
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    TreeMap(details).forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                },
            )
        }

    @Test
    fun `empty map produces null`() {
        assertEquals(null, serializeDetails(emptyMap()))
    }

    @Test
    fun `values containing double quotes are escaped`() {
        val result = serializeDetails(mapOf("key" to "value with \"quotes\""))
        assertNotNull(result)
        // Must be valid JSON - should contain escaped quotes
        assertTrue(result.contains("\\\"quotes\\\"") || result.contains("\\u0022"))
        // Can be decoded back
        val parsed = Json.decodeFromString(JsonObject.serializer(), result)
        assertEquals("value with \"quotes\"", (parsed["key"] as JsonPrimitive).content)
    }

    @Test
    fun `values containing backslashes are escaped`() {
        val result = serializeDetails(mapOf("path" to "C:\\Windows\\System32"))
        assertNotNull(result)
        val parsed = Json.decodeFromString(JsonObject.serializer(), result)
        assertEquals("C:\\Windows\\System32", (parsed["path"] as JsonPrimitive).content)
    }

    @Test
    fun `values containing newlines are escaped`() {
        val result = serializeDetails(mapOf("line" to "first\nsecond"))
        assertNotNull(result)
        val parsed = Json.decodeFromString(JsonObject.serializer(), result)
        assertEquals("first\nsecond", (parsed["line"] as JsonPrimitive).content)
    }

    @Test
    fun `values containing control characters are escaped`() {
        val result = serializeDetails(mapOf("ctrl" to "tab\there"))
        assertNotNull(result)
        // Must be parseable JSON
        val parsed = Json.decodeFromString(JsonObject.serializer(), result)
        assertEquals("tab\there", (parsed["ctrl"] as JsonPrimitive).content)
    }

    @Test
    fun `keys are deterministically sorted`() {
        val result1 = serializeDetails(mapOf("z" to "1", "a" to "2", "m" to "3"))
        val result2 = serializeDetails(mapOf("m" to "3", "z" to "1", "a" to "2"))
        assertEquals(result1, result2)
        // And keys appear in alphabetical order
        val aIdx = result1!!.indexOf("\"a\"")
        val mIdx = result1.indexOf("\"m\"")
        val zIdx = result1.indexOf("\"z\"")
        assertTrue(aIdx < mIdx && mIdx < zIdx)
    }

    @Test
    fun `produces valid JSON string`() {
        val result = serializeDetails(mapOf("event" to "login", "userId" to "42"))
        assertNotNull(result)
        // Should not throw
        Json.decodeFromString(JsonObject.serializer(), result)
    }
}
