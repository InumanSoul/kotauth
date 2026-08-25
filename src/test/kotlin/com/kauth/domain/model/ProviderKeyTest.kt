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
}
