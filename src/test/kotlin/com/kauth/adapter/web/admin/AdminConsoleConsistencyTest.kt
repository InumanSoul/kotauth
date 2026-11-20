package com.kauth.adapter.web.admin

import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the console-wide conventions from the 1.28 visual pass: one create verb, labels tied to
 * their fields, and enrollment links an admin can paste into a message.
 */
class AdminConsoleConsistencyTest {
    private fun adminViewSources(): Map<String, String> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/kauth/adapter/web/admin")
            if (candidate.isDirectory) {
                return candidate
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "kt" }
                    .associate { it.name to it.readText() }
            }
            dir = dir.parentFile
        }
        error("could not locate the admin view directory")
    }

    @Test
    fun `create flows say Create, never New or Add`() {
        // "Add Role" attaches an existing role to an app, so it is not a create flow.
        val createVerb =
            Regex(
                """"(\+ )?(New (User|Application|Workspace|Role|Group|API Key|Endpoint|Mapper|Attribute)""" +
                    """|Add (User|Application|Workspace|Group|API Key|Endpoint|Mapper|Claim Mapper|Attribute))\b""",
            )
        val offenders =
            adminViewSources().flatMap { (file, text) ->
                text
                    .lines()
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .flatMap { line -> createVerb.findAll(line).map { "$file: ${it.value}" }.toList() }
            }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `edit-row labels are real labels, except the ones naming a group of controls`() {
        // These head a checkbox group, a radio group, a colour pair or a read-only value, so there
        // is no single field for a <label for> to point at. Lower the number when one goes away.
        val groupHeadings = 10
        val spans =
            adminViewSources().values.sumOf { text ->
                Regex("""span\("edit-row__label"\)""").findAll(text).count()
            }
        assertTrue(spans <= groupHeadings, "found $spans span labels; use label(\"edit-row__label\") with htmlFor")
    }

    private val workspace =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @Test
    fun `the MFA enrollment link is absolute`() {
        val html =
            createHTML().html {
                mfaSettingsPageImpl(workspace, emptyList(), "admin", baseUrl = "https://auth.example.com/")()
            }
        assertTrue(html.contains("https://auth.example.com/t/acme/account/mfa/enroll"))
    }

    @Test
    fun `the passkey enrollment link is absolute`() {
        val html =
            createHTML().html {
                passkeysAdminPage(workspace, emptyList(), "admin", emptyList(), baseUrl = "https://auth.example.com")
            }
        assertTrue(html.contains("https://auth.example.com/t/acme/account/passkeys"))
    }
}
