package com.kauth.domain.service

import com.kauth.domain.model.CorsDecision
import com.kauth.fakes.FakeCorsPort
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CorsServiceTest {
    private val cors = FakeCorsPort()
    private val service = CorsService(cors)

    @BeforeTest
    fun reset() {
        cors.removePolicy("oriana")
        cors.removePolicy("acme")
    }

    @Test
    fun `discovery endpoint is public regardless of origin or tenant`() {
        val decision =
            service.decide(
                tenantSlug = "unknown",
                origin = "https://anywhere.example.com",
                endpointPath = "/.well-known/openid-configuration",
            )
        assertEquals(CorsDecision.Public, decision)
    }

    @Test
    fun `jwks endpoint is public regardless of origin or tenant`() {
        val decision =
            service.decide(
                tenantSlug = "unknown",
                origin = "https://anywhere.example.com",
                endpointPath = "/protocol/openid-connect/certs",
            )
        assertEquals(CorsDecision.Public, decision)
    }

    @Test
    fun `unknown tenant denies non-public endpoints`() {
        val decision =
            service.decide(
                tenantSlug = "does-not-exist",
                origin = "https://desk.oriana-platform.orb.local",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(CorsDecision.Denied, decision)
    }

    @Test
    fun `known tenant with matching origin is allowed`() {
        cors.setPolicy("oriana", setOf("https://desk.oriana-platform.orb.local"))

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://desk.oriana-platform.orb.local",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(
            CorsDecision.Allowed("https://desk.oriana-platform.orb.local", allowCredentials = false),
            decision,
        )
    }

    @Test
    fun `known tenant with non-matching origin is denied`() {
        cors.setPolicy("oriana", setOf("https://desk.oriana-platform.orb.local"))

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://evil.example.com",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(CorsDecision.Denied, decision)
    }

    @Test
    fun `tenant with allow_credentials enabled propagates to decision`() {
        cors.setPolicy(
            "oriana",
            setOf("https://desk.oriana-platform.orb.local"),
            allowCredentials = true,
        )

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://desk.oriana-platform.orb.local",
                endpointPath = "/protocol/openid-connect/userinfo",
            )
        assertEquals(
            CorsDecision.Allowed("https://desk.oriana-platform.orb.local", allowCredentials = true),
            decision,
        )
    }

    @Test
    fun `tenant with no registered origins denies everything non-public`() {
        cors.setPolicy("oriana", emptySet())

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://desk.oriana-platform.orb.local",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(CorsDecision.Denied, decision)
    }

    @Test
    fun `origin matching is exact - subdomain mismatch denied`() {
        cors.setPolicy("oriana", setOf("https://desk.oriana-platform.orb.local"))

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://evil.desk.oriana-platform.orb.local",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(CorsDecision.Denied, decision)
    }

    @Test
    fun `origin matching is exact - port mismatch denied`() {
        cors.setPolicy("oriana", setOf("https://desk.oriana-platform.orb.local"))

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "https://desk.oriana-platform.orb.local:8443",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(CorsDecision.Denied, decision)
    }

    @Test
    fun `localhost dev origin works when explicitly registered`() {
        cors.setPolicy("oriana", setOf("http://localhost:3000"))

        val decision =
            service.decide(
                tenantSlug = "oriana",
                origin = "http://localhost:3000",
                endpointPath = "/protocol/openid-connect/token",
            )
        assertEquals(
            CorsDecision.Allowed("http://localhost:3000", allowCredentials = false),
            decision,
        )
    }

    @Test
    fun `multiple origins in policy - any registered origin matches`() {
        cors.setPolicy(
            "oriana",
            setOf(
                "https://desk.oriana-platform.orb.local",
                "http://localhost:3000",
                "https://staging.oriana-platform.orb.local",
            ),
        )

        setOf(
            "https://desk.oriana-platform.orb.local",
            "http://localhost:3000",
            "https://staging.oriana-platform.orb.local",
        ).forEach { origin ->
            val decision = service.decide("oriana", origin, "/protocol/openid-connect/token")
            assertEquals(CorsDecision.Allowed(origin, allowCredentials = false), decision)
        }
    }
}
