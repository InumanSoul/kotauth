package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScimFieldsTest {
    private fun user() =
        User(
            id = UserId(1),
            tenantId = TenantId(1),
            username = "ada",
            email = "ada@example.com",
            fullName = "Ada Lovelace",
            passwordHash = User.SENTINEL_PASSWORD_HASH,
        )

    @Test
    fun `scim fields default to null so existing construction sites are unaffected`() {
        val u = user()
        assertNull(u.externalId)
        assertNull(u.givenName)
        assertNull(u.familyName)
        assertNull(Group(tenantId = TenantId(1), name = "eng").externalId)
    }

    @Test
    fun `scim fields round-trip through copy`() {
        val u = user().copy(externalId = "ext-1", givenName = "Ada", familyName = "Lovelace")
        assertEquals("ext-1", u.externalId)
        assertEquals("Ada", u.givenName)
        assertEquals("Lovelace", u.familyName)
    }

    @Test
    fun `a user provisioned without a password uses the sentinel hash`() {
        assertEquals("!", User.SENTINEL_PASSWORD_HASH)
        assertEquals(User.SENTINEL_PASSWORD_HASH, user().passwordHash)
    }
}
