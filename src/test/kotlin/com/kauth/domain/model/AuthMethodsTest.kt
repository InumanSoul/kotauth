package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthMethodsTest {
    @Test
    fun `a second factor adds otp and mfa once`() {
        assertEquals(listOf("pwd", "otp", "mfa"), AuthMethods.withSecondFactor(listOf("pwd")))
        assertEquals(listOf("otp", "mfa"), AuthMethods.withSecondFactor(listOf("otp")))
        assertEquals(listOf("fed", "otp", "mfa"), AuthMethods.withSecondFactor(listOf("fed")))
        assertEquals(listOf("otp", "mfa"), AuthMethods.withSecondFactor(emptyList()))
    }

    @Test
    fun `a passkey is hwk or swk, and mfa with user verification`() {
        assertEquals(listOf("hwk"), AuthMethods.forPasskey(synced = false, userVerified = false))
        assertEquals(listOf("swk", "mfa"), AuthMethods.forPasskey(synced = true, userVerified = true))
    }

    @Test
    fun `only a multi-factor sign-in earns the PAPE acr`() {
        assertEquals(AuthMethods.ACR_MULTI_FACTOR, AuthMethods.acrFor(listOf("pwd", "otp", "mfa")))
        assertNull(AuthMethods.acrFor(listOf("pwd")))
    }

    @Test
    fun `acr_values asks for multi-factor only with the exact PAPE value`() {
        assertTrue(AuthMethods.requestsMultiFactor("urn:other ${AuthMethods.ACR_MULTI_FACTOR}"))
        assertFalse(AuthMethods.requestsMultiFactor("${AuthMethods.ACR_MULTI_FACTOR}x"))
        assertFalse(AuthMethods.requestsMultiFactor(null))
    }
}
