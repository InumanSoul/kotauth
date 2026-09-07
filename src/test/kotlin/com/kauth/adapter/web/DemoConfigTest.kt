package com.kauth.adapter.web

import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DemoConfig] and [demoBanner].
 *
 * DemoConfig is a global volatile flag — tests must reset it after each run.
 */
class DemoConfigTest {
    @AfterTest
    fun reset() {
        DemoConfig.enabled = false
    }

    @Test
    fun `DemoConfig defaults to disabled`() {
        assertFalse(DemoConfig.enabled)
    }

    @Test
    fun `demoBanner produces no output when disabled`() {
        DemoConfig.enabled = false

        val html = renderBanner()

        assertFalse(html.contains("demo-banner"), "No banner markup when disabled")
    }

    @Test
    fun `demoBanner renders banner div when enabled`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertTrue(html.contains("demo-banner"))
    }

    @Test
    fun `demoBanner carries ARIA semantics for assistive tech`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertTrue(html.contains("role=\"status\""), "Banner should announce status via role")
        assertTrue(html.contains("aria-label=\"Demo mode\""), "Banner should label itself for screen readers")
    }

    @Test
    fun `demoBanner does not render credentials inline`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertFalse(html.contains("Demo1234!"), "Credentials must live in the demo docs repo, not in the UI")
        assertFalse(html.contains("sarah.chen"), "Credentials must live in the demo docs repo, not in the UI")
        assertFalse(html.contains("Data resets"), "Reset copy was removed with the visible banner text")
    }

    private fun renderBanner(): String =
        createHTML().html {
            body {
                demoBanner()
            }
        }
}
