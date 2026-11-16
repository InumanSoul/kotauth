package com.kauth.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResolveDbUrlTest {
    private fun resolve(vararg env: Pair<String, String>) = resolveDbUrl(mapOf(*env)::get)

    @Test
    fun `DB_URL wins over the parts`() {
        val url = resolve("DB_URL" to "jdbc:postgresql://db.internal:6543/prod", "DB_HOST" to "ignored")
        assertEquals("jdbc:postgresql://db.internal:6543/prod", url)
    }

    @Test
    fun `the parts alone build the URL`() {
        val url = resolve("DB_HOST" to "postgres", "DB_PORT" to "5433", "DB_NAME" to "kotauth")
        assertEquals("jdbc:postgresql://postgres:5433/kotauth", url)
    }

    @Test
    fun `nothing set keeps the previous default`() {
        assertEquals("jdbc:postgresql://localhost:5432/kauth_db", resolve())
    }

    @Test
    fun `each missing part defaults on its own`() {
        assertEquals("jdbc:postgresql://postgres:5432/kauth_db", resolve("DB_HOST" to "postgres"))
        assertEquals("jdbc:postgresql://localhost:5432/kotauth", resolve("DB_NAME" to "kotauth"))
    }

    @Test
    fun `a blank DB_URL falls back to the parts`() {
        assertEquals("jdbc:postgresql://postgres:5432/kauth_db", resolve("DB_URL" to " ", "DB_HOST" to "postgres"))
    }

    @Test
    fun `a non-numeric DB_PORT fails with a message naming it`() {
        val e = assertFailsWith<IllegalArgumentException> { resolve("DB_PORT" to "five") }
        assertTrue(e.message!!.contains("DB_PORT"))
    }

    @Test
    fun `an out-of-range DB_PORT fails`() {
        assertFailsWith<IllegalArgumentException> { resolve("DB_PORT" to "70000") }
        assertFailsWith<IllegalArgumentException> { resolve("DB_PORT" to "0") }
    }
}
