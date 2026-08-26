package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The detail values a refusal row is allowed to carry.
 *
 * [BrokeredSignInFailure.emailDomainOf] is the diagnostics-panel mirror of the gate's single-'@'
 * rule: an address that could be read as belonging to two domains must be recorded as belonging
 * to neither, or the panel names a domain the refused person is not in.
 */
class BrokeredSignInFailureTest {
    @Test
    fun `the domain of an ordinary address is its lower-cased tail`() {
        assertEquals("oriana.com.py", BrokeredSignInFailure.emailDomainOf("  Ada@Oriana.COM.PY  "))
    }

    @Test
    fun `an address with two at-signs has no domain`() {
        assertNull(BrokeredSignInFailure.emailDomainOf("mallory@evil.example@oriana.com.py"))
    }

    @Test
    fun `an address with no at-sign has no domain`() {
        assertNull(BrokeredSignInFailure.emailDomainOf("not-an-address"))
    }

    @Test
    fun `an address with a blank domain has no domain`() {
        assertNull(BrokeredSignInFailure.emailDomainOf("ada@"))
    }

    @Test
    fun `a null address has no domain`() {
        assertNull(BrokeredSignInFailure.emailDomainOf(null))
    }

    @Test
    fun `an error code outside the OAuth2 shape is dropped`() {
        assertEquals("access_denied", BrokeredSignInFailure.idpErrorCode("  access_denied "))
        assertNull(BrokeredSignInFailure.idpErrorCode("<script>alert(1)</script>"))
        assertNull(BrokeredSignInFailure.idpErrorCode("a".repeat(65)))
        assertNull(BrokeredSignInFailure.idpErrorCode(null))
    }
}
