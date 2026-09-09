package com.kauth.adapter.web.admin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A ratchet on the row-identity rule for admin tables.
 *
 * The workspace list linked the slug and left the display name as dead text; the application
 * list linked the client_id and left the name dead. Both read as database records rather than
 * as things, and in both the word an operator actually recognises was the one they could not
 * click. The rule is now: the row's identity leads column one and carries the link, as
 * `data-table__name`; the technical identifier sits beside it as inert `data-table__meta`.
 *
 * `.data-table__id` was the mechanism. It rendered a monospace accent link, so reaching for it
 * meant putting the link on the technical value — the class made the wrong layout the easy one.
 * It is deleted from `table.css`, and this test keeps it deleted.
 *
 * What this does NOT check is column order itself. Enforcing "column one carries the link"
 * would mean brace-matching the kotlinx.html DSL to find each table's first cell, and a number
 * of these tables are read-only lists (audit log, sessions, key rotation, diagnostics) with no
 * row link at all — so the check would need exceptions for exactly the rows most likely to be
 * edited. A brittle test that fails on innocent refactors is worse than the CSS comment
 * plus review. The two properties below are the ones a regex can hold honestly.
 */
class AdminTableIdentityTest {
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin/com/kauth/adapter/web").isDirectory) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    private fun sources(vararg relativeDirs: String): List<File> =
        relativeDirs.flatMap { rel ->
            File(repoRoot(), rel).walkTopDown().filter { it.isFile }.toList()
        }

    @Test
    fun `the retired data-table__id class does not come back`() {
        val offenders =
            sources("src/main/kotlin/com/kauth/adapter/web", "frontend/css")
                .filter { it.extension in setOf("kt", "css") }
                .filter { it.readText().contains("data-table__id") }
                .map { it.relativeTo(repoRoot()).path }

        assertTrue(
            offenders.isEmpty(),
            "data-table__id is retired: it styled a monospace accent link, so using it put the " +
                "row's link on a technical identifier instead of on the row's identity. Use " +
                "data-table__name for the identity link and data-table__meta for the identifier " +
                "beside it. Offenders: $offenders",
        )
    }

    @Test
    fun `no link is styled as inert meta text`() {
        // The mirror of the __id problem: __meta is muted, undecorated, and reads as dead text,
        // so a link wearing it gives the operator no reason to think it is clickable.
        val anchorWithMeta = Regex("""\ba\((?:[^()]|\([^()]*\))*data-table__meta""", RegexOption.DOT_MATCHES_ALL)
        val offenders =
            sources("src/main/kotlin/com/kauth/adapter/web")
                .filter { it.extension == "kt" }
                .filter { anchorWithMeta.containsMatchIn(it.readText()) }
                .map { it.relativeTo(repoRoot()).path }

        assertTrue(
            offenders.isEmpty(),
            "data-table__meta is inert secondary text — a link wearing it looks unclickable. " +
                "If it is the row's identity use data-table__name; otherwise it should not be a " +
                "link. Offenders: $offenders",
        )
    }
}
