package com.kauth.domain.util

import com.kauth.domain.model.TenantTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantThemeValidatorTest {
    @Test
    fun `default theme passes`() {
        assertNull(validateTenantTheme(TenantTheme.DEFAULT))
        assertNull(validateTenantTheme(TenantTheme.LIGHT))
    }

    @Test
    fun `color fields reject CSS injection payloads`() {
        val payloads =
            listOf(
                "#bad;color}",
                "#fff<script>",
                "#fff\"--",
                "url(evil)",
                "javascript:alert(1)",
                "#1FB\n",
                "#1FB\r",
                "&#0000",
            )
        payloads.forEach { payload ->
            val bad = TenantTheme.DEFAULT.copy(accentColor = payload)
            val error = validateTenantTheme(bad)
            assertTrue(
                error != null && "accentColor" in error,
                "Expected accentColor error for payload '$payload', got $error",
            )
        }
    }

    @Test
    fun `color fields accept canonical hex values`() {
        // CSS valid lengths: 3 (RGB), 4 (RGBA), 6 (RRGGBB), 8 (RRGGBBAA).
        listOf("#fff", "#fffA", "#1FBCFF", "#0AaEe8", "#1FBCFFAA").forEach { ok ->
            assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(accentColor = ok)))
        }
    }

    @Test
    fun `color fields reject non-CSS hex lengths`() {
        // 5 and 7 hex digits are NOT valid CSS — the validator must enforce {3,4,6,8} only.
        listOf("#fffff", "#fffffff", "#ff", "#f").forEach { bad ->
            val err = validateTenantTheme(TenantTheme.DEFAULT.copy(accentColor = bad))
            assertTrue(err != null && "accentColor" in err, "Expected accentColor error for '$bad', got $err")
        }
    }

    @Test
    fun `fontFamily rejects injection characters and accepts quoted names`() {
        listOf("Inter}{evil", "Inter; expression()", "Inter\nimport", "Inter<script>").forEach { bad ->
            val err = validateTenantTheme(TenantTheme.DEFAULT.copy(fontFamily = bad))
            assertTrue(err != null && "fontFamily" in err)
        }
        listOf("Inter", "Open Sans", "Source Code Pro", "Cormorant Garamond").forEach { ok ->
            assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(fontFamily = ok)))
        }
    }

    @Test
    fun `borderRadius rejects non-numeric units`() {
        listOf("8", "8;evil", "8 px", "url(8)", "8em;}").forEach { bad ->
            val err = validateTenantTheme(TenantTheme.DEFAULT.copy(borderRadius = bad))
            assertTrue(err != null && "borderRadius" in err)
        }
        listOf("8px", "0.5rem", "12em", "50%", "0px").forEach { ok ->
            assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(borderRadius = ok)))
        }
    }

    @Test
    fun `logoUrl rejects javascript and data schemes`() {
        listOf("javascript:alert(1)", "data:text/html,evil", "/relative/path", "//evil.com").forEach { bad ->
            val err = validateTenantTheme(TenantTheme.DEFAULT.copy(logoUrl = bad))
            assertTrue(err != null && "logoUrl" in err, "Expected logoUrl error for '$bad', got $err")
        }
        assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(logoUrl = "https://cdn.example.com/logo.svg")))
        assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(logoUrl = null)))
    }

    @Test
    fun `defaultLocale enforces BCP-47 minimal pattern`() {
        listOf("EN", "english", "en_US", "1n", "en-").forEach { bad ->
            val err = validateTenantTheme(TenantTheme.DEFAULT.copy(defaultLocale = bad))
            assertTrue(err != null && "defaultLocale" in err, "Expected defaultLocale error for '$bad', got $err")
        }
        // Either case in the region tag is accepted; downstream resolution normalizes.
        listOf("en", "es", "en-US", "pt-BR", "en-us").forEach { ok ->
            assertNull(validateTenantTheme(TenantTheme.DEFAULT.copy(defaultLocale = ok)))
        }
    }

    @Test
    fun `first failing field is the one reported`() {
        val bad = TenantTheme.DEFAULT.copy(accentColor = "}; evil", borderRadius = "8 px")
        assertEquals("accentColor must be a hex color like \"#1FBCFF\"", validateTenantTheme(bad))
    }
}
