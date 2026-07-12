package com.kauth.domain.port

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.ResourceServer
import com.kauth.domain.model.ResourceServerId
import com.kauth.domain.model.TenantId
import com.kauth.fakes.FakeResourceServerRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceServerRepositoryContractTest {
    private val tenantA = TenantId(1)
    private val tenantB = TenantId(2)
    private val clientInA = ApplicationId(100)
    private val clientInB = ApplicationId(200)

    private val repo = FakeResourceServerRepository()

    @BeforeTest
    fun setup() {
        repo.clear()
        repo.registerClient(clientInA, tenantA)
        repo.registerClient(clientInB, tenantB)
    }

    @Test
    fun `create + findByIdentifier round-trips a resource server`() {
        repo.create(tenantA, "payment-api", "Payment API", "billing service")
        val found = repo.findByIdentifier(tenantA, "payment-api")
        assertNotNull(found)
        assertEquals("Payment API", found.name)
        assertEquals(true, found.enabled)
    }

    @Test
    fun `findByIdentifier is tenant-scoped — tenant A cannot see tenant B's resource`() {
        repo.create(tenantA, "payment-api", "Payment API", null)
        repo.create(tenantB, "payment-api", "Other Payment API", null)

        val fromA = repo.findByIdentifier(tenantA, "payment-api")
        val fromB = repo.findByIdentifier(tenantB, "payment-api")

        assertNotNull(fromA)
        assertNotNull(fromB)
        assertEquals(tenantA, fromA.tenantId)
        assertEquals(tenantB, fromB.tenantId)
        assertEquals("Payment API", fromA.name)
        assertEquals("Other Payment API", fromB.name)
    }

    @Test
    fun `findByIdentifiers resolves a tenant-scoped batch`() {
        repo.create(tenantA, "payment-api", "Payment API", null)
        repo.create(tenantA, "ledger-api", "Ledger API", null)
        repo.create(tenantB, "payment-api", "Other Payment API", null)

        val found = repo.findByIdentifiers(tenantA, setOf("payment-api", "ledger-api", "missing"))

        assertEquals(listOf("payment-api", "ledger-api"), found.map { it.identifier })
    }

    @Test
    fun `findById is tenant-scoped — supplying a foreign-tenant id returns null`() {
        val createdInB = repo.create(tenantB, "ledger", "Ledger", null)
        val seen = repo.findById(tenantA, createdInB.id!!)
        assertNull(seen, "Tenant A must not be able to resolve tenant B's resource by raw id")
    }

    @Test
    fun `setAuthorizedResources rejects cross-tenant authorization`() {
        val tenantBResource = repo.create(tenantB, "ledger", "Ledger", null)

        val error = repo.setAuthorizedResources(clientInA, listOf(tenantBResource.id!!))
        assertIs<ResourceAuthorizationError.CrossTenant>(error)
        assertTrue(repo.listAuthorizedFor(clientInA).isEmpty(), "No authorization must persist on the failure path")
    }

    @Test
    fun `setAuthorizedResources rejects unknown resource server`() {
        val error = repo.setAuthorizedResources(clientInA, listOf(ResourceServerId(9999)))
        assertIs<ResourceAuthorizationError.UnknownResource>(error)
    }

    @Test
    fun `setAuthorizedResources rejects unknown client`() {
        val rs = repo.create(tenantA, "payment-api", "Payment API", null)
        val error = repo.setAuthorizedResources(ApplicationId(9999), listOf(rs.id!!))
        assertIs<ResourceAuthorizationError.UnknownClient>(error)
    }

    @Test
    fun `setAuthorizedResources persists the full set and replaces previous`() {
        val a = repo.create(tenantA, "payment-api", "Payment API", null)
        val b = repo.create(tenantA, "ledger-api", "Ledger API", null)
        val c = repo.create(tenantA, "analytics-api", "Analytics API", null)

        assertNull(repo.setAuthorizedResources(clientInA, listOf(a.id!!, b.id!!)))
        assertEquals(
            setOf("payment-api", "ledger-api"),
            repo.listAuthorizedFor(clientInA).map { it.identifier }.toSet(),
        )

        assertNull(repo.setAuthorizedResources(clientInA, listOf(b.id!!, c.id!!)))
        val refreshed = repo.listAuthorizedFor(clientInA).map { it.identifier }.toSet()
        assertEquals(setOf("ledger-api", "analytics-api"), refreshed, "Authorization replaces, not appends")
    }

    @Test
    fun `setEnabled toggles the enabled flag`() {
        val rs = repo.create(tenantA, "payment-api", "Payment API", null)
        repo.setEnabled(tenantA, rs.id!!, false)
        assertEquals(false, repo.findByIdentifier(tenantA, "payment-api")?.enabled)
        repo.setEnabled(tenantA, rs.id, true)
        assertEquals(true, repo.findByIdentifier(tenantA, "payment-api")?.enabled)
    }

    @Test
    fun `delete removes the resource server and clears its authorizations`() {
        val rs = repo.create(tenantA, "payment-api", "Payment API", null)
        repo.setAuthorizedResources(clientInA, listOf(rs.id!!))
        repo.delete(tenantA, rs.id)
        assertNull(repo.findByIdentifier(tenantA, "payment-api"))
        assertTrue(repo.listAuthorizedFor(clientInA).isEmpty())
    }

    @Test
    fun `findByTenantId returns only that tenant's records`() {
        repo.create(tenantA, "a1", "A1", null)
        repo.create(tenantA, "a2", "A2", null)
        repo.create(tenantB, "b1", "B1", null)

        assertEquals(2, repo.findByTenantId(tenantA).size)
        assertEquals(1, repo.findByTenantId(tenantB).size)
    }

    @Test
    fun `seed helper preserves the supplied id without colliding next sequential ids`() {
        repo.seed(
            ResourceServer(
                id = ResourceServerId(42),
                tenantId = tenantA,
                identifier = "pre-seeded",
                name = "Pre-seeded",
            ),
        )
        val next = repo.create(tenantA, "fresh", "Fresh", null)
        assertTrue(next.id!!.value > 42)
    }
}
