package com.kauth.adapter.web.admin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ratchet on the breadcrumb/heading split.
 *
 * `breadcrumb()` and `pageHeader()` used to be independent calls with independently typed strings,
 * and sixteen of thirty-seven admin pages had drifted — the console said "New Role" in the trail
 * and "Create Role" in the heading. `adminPage()` renders both from one source of truth, so the
 * trailing crumb defaults to the title and the two cannot silently disagree.
 *
 * Pages still calling `breadcrumb()` directly are legacy. The counts below only ever go down: a
 * new page that calls it directly pushes a file over its allowance and fails here. Convert the
 * page to `adminPage()` and lower the number instead of raising it.
 *
 * Reading the source rather than rendering is deliberate. Rendering every admin page would mean
 * constructing every page's arguments, which is far more code than the property is worth.
 */
class AdminPageIdentityTest {
    /**
     * Remaining direct `breadcrumb()` call sites per file. Lower these as pages move to
     * `adminPage()`; never raise one.
     */
    private val legacyAllowance =
        mapOf(
            "ApplicationViews.kt" to 3,
            "BackupViews.kt" to 2,
            "ClaimMapperViews.kt" to 1,
            "RbacViews.kt" to 6,
            "ResourceServerViews.kt" to 2,
            "SecurityViews.kt" to 4,
            "SettingsViews.kt" to 1,
            "UserViews.kt" to 4,
            "WebhookViews.kt" to 2,
            "WorkspaceViews.kt" to 5,
        )

    private fun adminViewDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/kauth/adapter/web/admin")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate the admin view directory")
    }

    private fun directBreadcrumbCalls(): Map<String, Int> =
        adminViewDir()
            .listFiles()
            .orEmpty()
            .filter { it.extension == "kt" && it.name != "AdminComponents.kt" }
            .associate { it.name to Regex("""\bbreadcrumb\(""").findAll(it.readText()).count() }
            .filterValues { it > 0 }

    @Test
    fun `no file gains a direct breadcrumb call`() {
        val actual = directBreadcrumbCalls()
        val regressions =
            actual.filter { (file, count) -> count > (legacyAllowance[file] ?: 0) }

        assertTrue(
            regressions.isEmpty(),
            "these files call breadcrumb() more than their allowance — use adminPage(), which " +
                "renders the trail and the heading from one title so they cannot disagree. " +
                "Offenders (file to actual count): $regressions. Allowance: $legacyAllowance",
        )
    }

    @Test
    fun `the allowance does not list files that have already been converted`() {
        val actual = directBreadcrumbCalls()
        val stale = legacyAllowance.filterKeys { it !in actual }

        assertTrue(
            stale.isEmpty(),
            "these files no longer call breadcrumb() directly — remove them from the allowance " +
                "so it keeps shrinking: ${stale.keys}",
        )
    }

    @Test
    fun `the allowance is not larger than the code`() {
        val actual = directBreadcrumbCalls()
        val loose =
            legacyAllowance.filter { (file, allowed) ->
                val real = actual[file]
                real != null && real < allowed
            }

        assertTrue(
            loose.isEmpty(),
            "the allowance is higher than the actual count for these files — lower it to lock the " +
                "progress in: ${loose.keys.map { it to (actual[it] to legacyAllowance[it]) }}",
        )
    }
}
