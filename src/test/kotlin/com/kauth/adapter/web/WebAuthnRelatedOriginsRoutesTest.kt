package com.kauth.adapter.web

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeTenantRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAuthnRelatedOriginsRoutesTest {
    @Test
    fun `the instance lists every workspace domain allowed to use its passkeys`() =
        testApplication {
            val tenants =
                FakeTenantRepository().apply {
                    add(
                        Tenant(
                            id = TenantId(0),
                            slug = "acme",
                            displayName = "Acme",
                            issuerUrl = null,
                            publicBaseUrl = "https://auth.acme.test",
                        ),
                    )
                    add(Tenant(id = TenantId(0), slug = "beta", displayName = "Beta", issuerUrl = null))
                    add(
                        Tenant(
                            id = TenantId(0),
                            slug = "gamma",
                            displayName = "Gamma",
                            issuerUrl = null,
                            publicBaseUrl = "https://id.gamma.test",
                        ),
                    )
                }
            application { routing { webAuthnRelatedOriginsRoutes(tenants) } }

            val response = client.get("/.well-known/webauthn")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            assertEquals("""{"origins":["https://auth.acme.test","https://id.gamma.test"]}""", response.bodyAsText())
        }
}
