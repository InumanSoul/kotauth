package com.kauth.config

import com.kauth.adapter.web.scim.scimDialectFor
import com.kauth.adapter.web.scim.scimDialects
import com.kauth.domain.model.ApiKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapApiKeysTest {
    // Named from the registry rather than spelled out: the vendor ids live in one package.
    private val nonDefaultDialect = scimDialects.last().id

    private fun entryJson(dialect: String?): String {
        val dialectField = dialect?.let { ",\"scimDialect\":\"$it\"" } ?: ""
        return "[{\"tenant\":\"zion\",\"name\":\"zion-scim\",\"scopes\":[\"scim\"]," +
            "\"keyHash\":\"hash-v1\"$dialectField}]"
    }

    @Test
    fun `a registered scimDialect is carried onto the entry`() {
        assertNotEquals(ApiKey.DEFAULT_SCIM_DIALECT, nonDefaultDialect)

        assertEquals(nonDefaultDialect, parseBootstrapApiKeyEntries(entryJson(nonDefaultDialect)).single().scimDialect)
    }

    @Test
    fun `an entry with no scimDialect field carries none`() {
        assertNull(parseBootstrapApiKeyEntries(entryJson(null)).single().scimDialect)
    }

    @Test
    fun `an unregistered scimDialect fails the parse instead of resolving to the pass-through`() {
        // The request path deliberately falls back for an unregistered stored id, so a typo here
        // would otherwise boot cleanly and surface only as payloads parsed by the wrong dialect.
        val typo = "a-dialect-this-build-does-not-ship"
        assertEquals(ApiKey.DEFAULT_SCIM_DIALECT, scimDialectFor(typo).id)

        val failure =
            assertFailsWith<IllegalArgumentException> { parseBootstrapApiKeyEntries(entryJson(typo)) }

        assertTrue(failure.message!!.contains(typo), failure.message)
        assertTrue(failure.message!!.contains("zion-scim"), failure.message)
    }

    @Test
    fun `an empty scimDialect fails the parse rather than meaning the default`() {
        assertFailsWith<IllegalArgumentException> { parseBootstrapApiKeyEntries(entryJson("")) }
    }
}
