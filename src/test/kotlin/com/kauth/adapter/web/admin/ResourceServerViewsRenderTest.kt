package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * View-layer tests for the APIs pages in [ResourceServerViews].
 *
 * The APIs nav entry lives in the Applications rail (see AdminShell.renderAppsCtxPanel),
 * not the Settings rail. These pages must render with `activeRail = "apps"` so the "Apps"
 * rail icon — and the "APIs" entry inside it — actually highlight, instead of switching to
 * a Settings rail that no longer contains an APIs link.
 */
class ResourceServerViewsRenderTest {
    private fun render(page: HTML.() -> Unit): String = createHTML().html { page() }

    private val workspace =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @Test
    fun `resource servers list page marks the Apps rail active, not Settings`() {
        val html =
            render(
                resourceServersListPageImpl(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                    resources = emptyList(),
                ),
            )

        assertTrue(
            html.contains("title=\"Apps\" class=\"rail__item rail__item--active\"") ||
                html.contains("class=\"rail__item rail__item--active\" title=\"Apps\""),
            "Apps rail icon must be highlighted",
        )
        assertFalse(
            html.contains("title=\"Settings\" class=\"rail__item rail__item--active\"") ||
                html.contains("class=\"rail__item rail__item--active\" title=\"Settings\""),
            "Settings rail icon must NOT be highlighted on the APIs page",
        )
        assertTrue(
            Regex("<a href=\"[^\"]*apis[^\"]*\"[^>]*sidebar__item--active").containsMatchIn(html) ||
                Regex("sidebar__item--active[^>]*>\\s*<span>\\s*APIs").containsMatchIn(html),
            "The APIs entry in the Applications context panel must be highlighted",
        )
    }

    @Test
    fun `resource server form page marks the Apps rail active, not Settings`() {
        val html =
            render(
                resourceServerFormPageImpl(
                    workspace = workspace,
                    allWorkspaces = emptyList(),
                    loggedInAs = "admin",
                ),
            )

        assertTrue(
            html.contains("title=\"Apps\" class=\"rail__item rail__item--active\"") ||
                html.contains("class=\"rail__item rail__item--active\" title=\"Apps\""),
            "Apps rail icon must be highlighted",
        )
        assertFalse(
            html.contains("title=\"Settings\" class=\"rail__item rail__item--active\"") ||
                html.contains("class=\"rail__item rail__item--active\" title=\"Settings\""),
            "Settings rail icon must NOT be highlighted on the APIs page",
        )
    }
}
