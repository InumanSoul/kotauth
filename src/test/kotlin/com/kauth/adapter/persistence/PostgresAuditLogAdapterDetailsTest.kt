package com.kauth.adapter.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresAuditLogAdapterDetailsTest {
    private fun roundTrip(value: String): String {
        val json = serializeAuditDetails(mapOf("k" to value))
        assertNotNull(json)
        val parsed = Json.decodeFromString(JsonObject.serializer(), json)
        return (parsed["k"] as JsonPrimitive).content
    }

    @Test
    fun `empty map produces null`() {
        assertEquals(null, serializeAuditDetails(emptyMap()))
    }

    @Test
    fun `double quotes round-trip`() {
        val v = "value with \"quotes\""
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `backslashes round-trip`() {
        val v = "C:\\Windows\\System32"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `newline round-trips`() {
        val v = "first\nsecond"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `tab round-trips`() {
        val v = "a\tb"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `null byte round-trips`() {
        val v = "a\u0000b"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `unicode line separator U_2028 round-trips`() {
        val v = "first\u2028second"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `unicode paragraph separator U_2029 round-trips`() {
        val v = "first\u2029second"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `bmp boundary U_FFFD round-trips`() {
        val v = "rune \uFFFD ok"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `surrogate pair above BMP round-trips`() {
        val v = "hi \uD83D\uDE00"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `empty string value round-trips`() {
        assertEquals("", roundTrip(""))
    }

    @Test
    fun `pipe character round-trips`() {
        val v = "user|agent"
        assertEquals(v, roundTrip(v))
    }

    @Test
    fun `keys are deterministically sorted regardless of input order`() {
        val a = serializeAuditDetails(mapOf("z" to "1", "a" to "2", "m" to "3"))
        val b = serializeAuditDetails(mapOf("m" to "3", "z" to "1", "a" to "2"))
        assertEquals(a, b)
        assertNotNull(a)
        val aIdx = a.indexOf("\"a\"")
        val mIdx = a.indexOf("\"m\"")
        val zIdx = a.indexOf("\"z\"")
        assertTrue(aIdx < mIdx && mIdx < zIdx)
    }
}
