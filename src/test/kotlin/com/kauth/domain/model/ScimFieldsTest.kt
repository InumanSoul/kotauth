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
    fun `a provisioned user carries an external id alongside the sentinel password hash`() {
        val provisioned =
            user().copy(
                externalId = "ext-1",
                givenName = "Ada",
                familyName = "Lovelace",
            )

        // A provisioned user has no local password but must still be a valid User.
        assertEquals(User.SENTINEL_PASSWORD_HASH, provisioned.passwordHash)
        assertEquals("ext-1", provisioned.externalId)
        assertEquals("Ada Lovelace", "${provisioned.givenName} ${provisioned.familyName}")
    }
}
