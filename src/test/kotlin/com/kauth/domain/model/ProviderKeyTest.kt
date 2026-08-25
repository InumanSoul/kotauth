package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderKeyTest {
    @Test
    fun `a provider key accepts the reserved and well-formed keys and rejects the rest`() {
        assertEquals("google", ProviderKey.of("google")?.value)
        assertEquals("oriana-entra", ProviderKey.of("oriana-entra")?.value)
        assertNull(ProviderKey.of("Oriana")) // upper case is not URL-safe here
        assertNull(ProviderKey.of("entra_id")) // underscore is outside the pattern
        assertNull(ProviderKey.of(""))
        assertNull(ProviderKey.of("a".repeat(33))) // would not fit varchar(32)
    }

    @Test
    fun `RESERVED names exactly the two keys that have a compiled-in adapter`() {
        // Every guard in this phase is written as `it in RESERVED`, and three `when` blocks treat
        // RESERVED membership as their exhaustiveness argument. Emptying or widening this set
        // silently re-points all of them, so its contents and order are pinned here directly.
        assertEquals(setOf(ProviderKey.GOOGLE, ProviderKey.GITHUB), ProviderKey.RESERVED)
        assertEquals(
            listOf("google", "github"),
            ProviderKey.RESERVED.map { it.value },
            "Order is load-bearing: the identity-provider cards render in iteration order",
        )
    }

    @Test
    fun `a well-formed key that is not reserved parses but is not reserved`() {
        // The exact shape every guard relies on: parsing succeeds, membership does not.
        val okta = ProviderKey.of("okta")
        assertEquals("okta", okta?.value)
        assertEquals(false, okta in ProviderKey.RESERVED)
    }
}
