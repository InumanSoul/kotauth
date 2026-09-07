package com.kauth.adapter.web.admin

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The Applications context panel.
 *
 * `apps` defaulted to `emptyList()`, so a page that never loaded the application list
 * was indistinguishable from a workspace that had none — and six pages on the Apps rail
 * do not load it. Each of them told the operator "No applications yet" over a workspace
 * with applications in it. Null now means unknown.
 */
class AppsContextPanelTest {
    private fun panel(apps: List<Pair<String, String>>?): String =
        createHTML().div {
            renderAppsCtxPanel(
                workspaceSlug = "acme",
                apps = apps,
                activeAppSlug = null,
            )
        }

    @Test
    fun `claims nothing about applications it was never given`() {
        val html = panel(null)

        assertFalse(html.contains("No applications yet"), "An unloaded list is not an empty one")
        assertContains(html, "View applications")
    }

    @Test
    fun `offers the create action only when the workspace genuinely has none`() {
        val html = panel(emptyList())

        assertContains(html, "No applications yet")
        assertContains(html, "applications/new")
    }

    @Test
    fun `lists the applications it was given`() {
        val html = panel(listOf("acme-dashboard" to "Acme Dashboard", "acme-mobile" to "Acme Mobile App"))

        assertContains(html, "Acme Dashboard")
        assertContains(html, "Acme Mobile App")
        assertFalse(html.contains("No applications yet"))
    }
}
