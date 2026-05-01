package com.kauth.adapter.web.portal

import com.kauth.domain.model.PortalConfig
import com.kauth.domain.model.PortalLayout
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.LauncherService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [launcherRoutes] — auth gate and tenant resolution.
 *
 * Page-render concerns (apps grid, empty state, target=_blank) are covered
 * by [LauncherViewRenderTest], which calls [PortalView.launcherPage] directly
 * — sidestepping session-cookie machinery that doesn't add coverage.
 */
class LauncherRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val appRepo = FakeApplicationRepository()
    private val roleRepo = FakeRoleRepository()

    private val tenant =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
            portalConfig = PortalConfig(layout = PortalLayout.SIDEBAR),
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        appRepo.clear()
        roleRepo.clear()
        tenantRepo.add(tenant)
    }

    @Test
    fun `redirects to login when no session cookie is present`() =
        testApplication {
            application { installTestApp() }

            val noFollow = createClient { followRedirects = false }
            val response = noFollow.get("/t/acme/launcher")

            assertEquals(HttpStatusCode.Found, response.status)
            assertTrue(
                response.headers["Location"]?.contains("/t/acme/account/login") == true,
                "Must redirect to portal login",
            )
        }

    @Test
    fun `returns 404 when tenant does not exist`() =
        testApplication {
            application { installTestApp() }

            val response = client.get("/t/unknown/launcher")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun io.ktor.server.application.Application.installTestApp() {
        install(Sessions) {
            cookie<PortalSession>("KOTAUTH_PORTAL")
        }
        routing {
            launcherRoutes(
                launcherService = LauncherService(appRepo, roleRepo),
                tenantRepository = tenantRepo,
            )
        }
    }
}
