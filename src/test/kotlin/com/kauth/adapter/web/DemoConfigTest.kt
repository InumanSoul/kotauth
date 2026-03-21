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
        assertTrue(html.contains("badge badge--warn"))
        assertTrue(html.contains("Demo"))
    }

    @Test
    fun `demoBanner contains admin credentials`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertTrue(html.contains("admin"), "Should show admin username")
        assertTrue(html.contains("changeme123!"), "Should show admin password")
    }

    @Test
    fun `demoBanner contains acme user credentials`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertTrue(html.contains("sarah.chen"), "Should show Acme demo username")
        assertTrue(html.contains("Demo1234!"), "Should show Acme demo password")
    }

    @Test
    fun `demoBanner mentions data resets`() {
        DemoConfig.enabled = true

        val html = renderBanner()

        assertTrue(html.contains("Data resets periodically"))
    }

    private fun renderBanner(): String =
        createHTML().html {
            body {
                demoBanner()
            }
        }
}
