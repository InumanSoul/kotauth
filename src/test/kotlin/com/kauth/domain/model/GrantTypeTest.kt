package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GrantTypeTest {
    @Test
    fun `fromValue maps the wire names used by the token endpoint`() {
        assertEquals(GrantType.AUTHORIZATION_CODE, GrantType.fromValue("authorization_code"))
        assertEquals(GrantType.CLIENT_CREDENTIALS, GrantType.fromValue("client_credentials"))
        assertEquals(GrantType.REFRESH_TOKEN, GrantType.fromValue("refresh_token"))
    }

    @Test
    fun `fromValue returns null for an unknown grant rather than defaulting`() {
        assertNull(GrantType.fromValue("password"))
        assertNull(GrantType.fromValue(""))
    }

    @Test
    fun `defaultsFor mirrors the V59 backfill`() {
        assertEquals(
            setOf(GrantType.AUTHORIZATION_CODE, GrantType.CLIENT_CREDENTIALS, GrantType.REFRESH_TOKEN),
            GrantType.defaultsFor(AccessType.CONFIDENTIAL),
        )
        assertEquals(
            setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
            GrantType.defaultsFor(AccessType.PUBLIC),
        )
        assertEquals(emptySet(), GrantType.defaultsFor(AccessType.BEARER_ONLY))
    }

    @Test
    fun `application defaults to no grants so every construction site is explicit`() {
        val app =
            Application(
                id = ApplicationId(1),
                tenantId = TenantId(1),
                clientId = "c",
                name = "C",
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = true,
            )
        assertEquals(emptySet(), app.grantTypes)
    }
}
