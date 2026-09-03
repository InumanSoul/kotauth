package com.kauth.adapter.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the inline SVG icon set.
 *
 * [inlineSvgIcon] renders nothing when the named resource is missing, and caches that
 * miss, so a typo or a deleted file produces a silently blank box rather than an error.
 * An icon that hardcodes its own paint fails the same way in practice: it renders, but
 * at 20% white on a dark card it reads as blank. Neither shows up in a diff.
 *
 * The reference scan reads only the two unambiguous call forms. Names reached through a
 * `when` branch — the rail keys, the provider logos — are covered from the other side by
 * [every shipped icon is referenced by a view], which fails if such a branch is misspelt
 * or its icon deleted.
 */
class IconManifestTest {
    private val iconDir = File("src/main/resources/static/icons")
    private val viewSourceRoot = File("src/main/kotlin/com/kauth/adapter/web")

    /** Brand marks carry their own palette; every other icon inherits the text colour. */
    private val multiColourBrandMarks = setOf("google-logo", "github-logo")

    private fun shippedIcons(): List<File> =
        iconDir.listFiles { f -> f.extension == "svg" }.orEmpty().sortedBy { it.name }

    private fun viewSources(): List<String> =
        viewSourceRoot
            .walkTopDown()
            .filter { it.extension == "kt" }
            .map { it.readText() }
            .toList()

    @Test
    fun `every icon name a view asks for has a file behind it`() {
        val shipped = shippedIcons().map { it.nameWithoutExtension }.toSet()
        val referenced =
            viewSources()
                .flatMap { source ->
                    ICON_CALL.findAll(source).map { it.groupValues[1] } +
                        NAMED_ICON_ARGUMENT.findAll(source).map { it.groupValues[1] }
                }.toSet()

        val missing = (referenced - shipped).sorted()
        assertTrue(
            missing.isEmpty(),
            "Views ask for icons with no file in ${iconDir.path}: $missing — these render as blank boxes, not errors.",
        )
    }

    @Test
    fun `every shipped icon is referenced by a view`() {
        val sources = viewSources()
        val orphans =
            shippedIcons()
                .map { it.nameWithoutExtension }
                .filterNot { name -> sources.any { it.contains("\"$name\"") } }
                .sorted()

        assertTrue(
            orphans.isEmpty(),
            "Icons ship but no view names them: $orphans — either dead weight, or a `when` branch " +
                "that maps to an icon was misspelt and now renders blank.",
        )
    }

    @Test
    fun `every icon inherits its colour from the surrounding text`() {
        val offenders =
            shippedIcons()
                .filterNot { it.nameWithoutExtension in multiColourBrandMarks }
                .filter { file ->
                    PAINT_ATTRIBUTE
                        .findAll(file.readText())
                        .any { it.groupValues[2] !in ACCEPTABLE_PAINTS }
                }.map { it.name }
                .sorted()

        assertTrue(
            offenders.isEmpty(),
            "Icons hardcode a paint colour instead of currentColor: $offenders — these render " +
                "invisibly against surfaces the hardcoded colour was not chosen for.",
        )
    }

    private companion object {
        val ACCEPTABLE_PAINTS = setOf("currentColor", "none")
        val PAINT_ATTRIBUTE = Regex("""\b(stroke|fill)="([^"]*)"""")
        val ICON_CALL = Regex("""inlineSvgIcon\(\s*"([a-z0-9-]+)"""")
        val NAMED_ICON_ARGUMENT = Regex("""iconName\s*=\s*"([a-z0-9-]+)"""")
    }
}
