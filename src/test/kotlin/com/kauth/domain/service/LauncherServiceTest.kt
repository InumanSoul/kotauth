package com.kauth.domain.service

import com.kauth.domain.model.AccessType
import com.kauth.domain.model.Application
import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeRoleRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [LauncherService].
 *
 * Covers visibility rules (enabled/launcherVisible/launcherUrl),
 * client-scoped role entitlement, and sort order.
 */
class LauncherServiceTest {
    private val apps = FakeApplicationRepository()
    private val roles = FakeRoleRepository()
    private val svc = LauncherService(apps, roles)

    private val tenantId = TenantId(1)
    private val otherTenantId = TenantId(2)
    private val alice = UserId(10)
    private val bob = UserId(11)

    @BeforeTest
    fun setup() {
        apps.clear()
        roles.clear()
    }

    private fun launchableApp(
        id: Int,
        name: String = "App $id",
        clientId: String = "app-$id",
        launcherUrl: String? = "http://localhost/$id",
        launcherVisible: Boolean = true,
        enabled: Boolean = true,
        order: Int = 0,
        tenant: TenantId = tenantId,
    ): Application =
        apps.add(
            Application(
                id = ApplicationId(id),
                tenantId = tenant,
                clientId = clientId,
                name = name,
                description = null,
                accessType = AccessType.PUBLIC,
                enabled = enabled,
                redirectUris = listOf("http://localhost/cb"),
                launcherUrl = launcherUrl,
                launcherVisible = launcherVisible,
                launcherDisplayOrder = order,
            ),
        )

    @Test
    fun `app with no client-scoped roles is visible to everyone`() {
        launchableApp(100, name = "Open App")
        val result = svc.resolveLauncherApps(alice, tenantId)
        assertEquals(listOf("Open App"), result.map { it.name })
    }

    @Test
    fun `app with client-scoped role is hidden from users without that role`() {
        launchableApp(100, name = "Restricted App")
        roles.add(
            Role(
                tenantId = tenantId,
                name = "viewer",
                scope = RoleScope.CLIENT,
                clientId = ApplicationId(100),
            ),
        )
        val result = svc.resolveLauncherApps(alice, tenantId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `app with client-scoped role is visible to users holding that role`() {
        launchableApp(100, name = "Restricted App")
        val role =
            roles.add(
                Role(
                    tenantId = tenantId,
                    name = "viewer",
                    scope = RoleScope.CLIENT,
                    clientId = ApplicationId(100),
                ),
            )
        roles.assignRoleToUser(alice, role.id!!)
        val result = svc.resolveLauncherApps(alice, tenantId)
        assertEquals(listOf("Restricted App"), result.map { it.name })
    }

    @Test
    fun `disabled app is hidden`() {
        launchableApp(100, enabled = false)
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }

    @Test
    fun `launcherVisible false hides the app`() {
        launchableApp(100, launcherVisible = false)
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }

    @Test
    fun `app with null launcherUrl is hidden`() {
        launchableApp(100, launcherUrl = null)
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }

    @Test
    fun `app with blank launcherUrl is hidden`() {
        launchableApp(100, launcherUrl = "   ")
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }

    @Test
    fun `apps from other tenants are excluded`() {
        launchableApp(100, name = "Mine", tenant = tenantId)
        launchableApp(200, name = "Theirs", tenant = otherTenantId)
        val result = svc.resolveLauncherApps(alice, tenantId)
        assertEquals(listOf("Mine"), result.map { it.name })
    }

    @Test
    fun `sorts by displayOrder then name`() {
        launchableApp(100, name = "Bravo", order = 5)
        launchableApp(101, name = "Alpha", order = 5)
        launchableApp(102, name = "Charlie", order = 1)
        launchableApp(103, name = "Delta", order = 10)

        val result = svc.resolveLauncherApps(alice, tenantId)
        assertEquals(listOf("Charlie", "Alpha", "Bravo", "Delta"), result.map { it.name })
    }

    @Test
    fun `tenant-scoped role does not grant access to client-restricted app`() {
        launchableApp(100, name = "Restricted App")
        // Add a client-scoped role on the app — but assign user a tenant-scoped role only.
        roles.add(
            Role(
                tenantId = tenantId,
                name = "viewer",
                scope = RoleScope.CLIENT,
                clientId = ApplicationId(100),
            ),
        )
        val tenantRole =
            roles.add(
                Role(
                    tenantId = tenantId,
                    name = "admin",
                    scope = RoleScope.TENANT,
                ),
            )
        roles.assignRoleToUser(alice, tenantRole.id!!)
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }

    @Test
    fun `composite role grants access via expanded child role`() {
        launchableApp(100, name = "Restricted App")
        val viewer =
            roles.add(
                Role(
                    tenantId = tenantId,
                    name = "viewer",
                    scope = RoleScope.CLIENT,
                    clientId = ApplicationId(100),
                ),
            )
        val composite =
            roles.add(
                Role(
                    tenantId = tenantId,
                    name = "admin",
                    scope = RoleScope.TENANT,
                ),
            )
        roles.addChildRole(composite.id!!, viewer.id!!)
        roles.assignRoleToUser(alice, composite.id)

        val result = svc.resolveLauncherApps(alice, tenantId)
        assertEquals(listOf("Restricted App"), result.map { it.name })
    }

    @Test
    fun `mixed catalog returns only entitled and visible apps in order`() {
        launchableApp(100, name = "Public Tool", order = 1)
        launchableApp(200, name = "Restricted A", order = 2)
        launchableApp(300, name = "Restricted B", order = 3)
        launchableApp(400, name = "Hidden", launcherVisible = false, order = 4)

        roles.add(Role(tenantId = tenantId, name = "a", scope = RoleScope.CLIENT, clientId = ApplicationId(200)))
        val rb =
            roles.add(
                Role(tenantId = tenantId, name = "b", scope = RoleScope.CLIENT, clientId = ApplicationId(300)),
            )
        roles.assignRoleToUser(bob, rb.id!!)

        val aliceApps = svc.resolveLauncherApps(alice, tenantId).map { it.name }
        val bobApps = svc.resolveLauncherApps(bob, tenantId).map { it.name }

        assertEquals(listOf("Public Tool"), aliceApps)
        assertEquals(listOf("Public Tool", "Restricted B"), bobApps)
    }

    @Test
    fun `empty tenant returns empty list`() {
        assertTrue(svc.resolveLauncherApps(alice, tenantId).isEmpty())
    }
}
