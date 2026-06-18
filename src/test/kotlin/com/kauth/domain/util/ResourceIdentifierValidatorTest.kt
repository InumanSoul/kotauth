package com.kauth.domain.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResourceIdentifierValidatorTest {
    @Test
    fun `accepts a stable opaque slug`() {
        assertNull(validateResourceIdentifier("payment-api"))
        assertNull(validateResourceIdentifier("ledger_api"))
        assertNull(validateResourceIdentifier("v1.payments"))
    }

    @Test
    fun `accepts an absolute https URI with a host`() {
        assertNull(validateResourceIdentifier("https://api.example.com"))
        assertNull(validateResourceIdentifier("https://api.example.com/v1"))
    }

    @Test
    fun `rejects blank or whitespace-only identifiers`() {
        assertNotNull(validateResourceIdentifier(""))
        assertNotNull(validateResourceIdentifier("   "))
    }

    @Test
    fun `rejects identifiers containing internal whitespace`() {
        val result = validateResourceIdentifier("payment api")
        assertNotNull(result)
        assertEquals("identifier may not contain whitespace", result)
    }

    @Test
    fun `rejects http URIs missing a host`() {
        assertNotNull(validateResourceIdentifier("https://"))
        assertNotNull(validateResourceIdentifier("http:///path"))
    }

    @Test
    fun `rejects identifiers longer than the column limit`() {
        val tooLong = "a".repeat(256)
        assertNotNull(validateResourceIdentifier(tooLong))
    }
}
