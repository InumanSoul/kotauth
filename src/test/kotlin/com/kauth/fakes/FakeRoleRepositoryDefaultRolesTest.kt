package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the client-default-roles contract on [FakeRoleRepository] — the
 * v1.11.0 registration-grant logic depends on replace-semantics and
 * name-sorted reads, so they are verified here directly.
 */
class FakeRoleRepositoryDefaultRolesTest {
    private val roles = FakeRoleRepository()
    private val tenantId = TenantId(1)
    private val clientId = ApplicationId(10)

    private fun role(name: String) = roles.add(Role(tenantId = tenantId, name = name, scope = RoleScope.TENANT))

    @BeforeTest
    fun setUp() {
        roles.clear()
    }

    @Test
    fun `findDefaultRolesForClient is empty when none configured`() {
        assertTrue(roles.findDefaultRolesForClient(clientId).isEmpty())
    }

    @Test
    fun `setDefaultRolesForClient round-trips and reads back sorted by name`() {
        val viewer = role("viewer")
        val applicant = role("applicant")
        roles.setDefaultRolesForClient(clientId, listOf(viewer.id!!, applicant.id!!))

        val found = roles.findDefaultRolesForClient(clientId)

        assertEquals(listOf("applicant", "viewer"), found.map { it.name })
    }

    @Test
    fun `setDefaultRolesForClient replaces the prior set, never appends`() {
        val first = role("first")
        val second = role("second")
        roles.setDefaultRolesForClient(clientId, listOf(first.id!!))
        roles.setDefaultRolesForClient(clientId, listOf(second.id!!))

        val found = roles.findDefaultRolesForClient(clientId)

        assertEquals(listOf("second"), found.map { it.name })
    }

    @Test
    fun `setDefaultRolesForClient with an empty list clears the configuration`() {
        val viewer = role("viewer")
        roles.setDefaultRolesForClient(clientId, listOf(viewer.id!!))
        roles.setDefaultRolesForClient(clientId, emptyList())

        assertTrue(roles.findDefaultRolesForClient(clientId).isEmpty())
    }

    @Test
    fun `setDefaultRolesForClient de-duplicates repeated role ids`() {
        val viewer = role("viewer")
        roles.setDefaultRolesForClient(clientId, listOf(viewer.id!!, viewer.id))

        assertEquals(1, roles.findDefaultRolesForClient(clientId).size)
    }

    @Test
    fun `default roles are scoped per client`() {
        val viewer = role("viewer")
        roles.setDefaultRolesForClient(clientId, listOf(viewer.id!!))

        assertTrue(roles.findDefaultRolesForClient(ApplicationId(99)).isEmpty())
    }
}
