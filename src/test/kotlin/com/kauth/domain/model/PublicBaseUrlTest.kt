package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PublicBaseUrlTest {
    private fun valid(raw: String) = (assertIs<PublicBaseUrl.Result.Valid>(PublicBaseUrl.parse(raw))).origin

    private fun invalid(raw: String) = assertIs<PublicBaseUrl.Result.Invalid>(PublicBaseUrl.parse(raw))

    @Test
    fun `an https origin is normalized to lowercase without a trailing slash`() {
        assertEquals("https://auth.example.com", valid(" HTTPS://Auth.Example.com/ "))
        assertEquals("https://auth.example.com:8443", valid("https://auth.example.com:8443"))
    }

    @Test
    fun `plain http is accepted only for local development hosts`() {
        assertEquals("http://localhost:8082", valid("http://localhost:8082"))
        assertEquals("http://auth.localhost", valid("http://auth.localhost"))
        invalid("http://auth.example.com")
    }

    @Test
    fun `anything beyond an origin is refused`() {
        invalid("https://auth.example.com/t/acme")
        invalid("https://auth.example.com?x=1")
        invalid("https://user:pass@auth.example.com")
        invalid("auth.example.com")
        invalid("ftp://auth.example.com")
    }

    @Test
    fun `a workspace builds its prefix on its own origin, else the instance's`() {
        val tenant = Tenant(id = TenantId(1), slug = "acme", displayName = "Acme", issuerUrl = null)

        assertEquals("https://sso.example.com/t/acme", tenant.publicPrefix("https://sso.example.com/"))
        assertEquals(
            "https://auth.acme.com/t/acme",
            tenant.copy(publicBaseUrl = "https://auth.acme.com").publicPrefix("https://sso.example.com"),
        )
    }
}
