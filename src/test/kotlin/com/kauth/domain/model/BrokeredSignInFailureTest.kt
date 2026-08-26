package com.kauth.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detail values a refusal row is allowed to carry.
 *
 * [BrokeredSignInFailure.emailDomainOf] is the diagnostics-panel mirror of the gate's single-'@'
 * rule: an address that could be read as belonging to two domains must be recorded as belonging
 * to neither, or the panel names a domain the refused person is not in.
 */
class BrokeredSignInFailureTest {
    private val oriana = requireNotNull(ProviderKey.of("oriana"))

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

    @Test
    fun `the same identity always gets the same reference`() {
        val hasher = BrokeredReferenceHasher("instance-secret-key-0123456789012")
        assertEquals(
            hasher.of(TenantId(1), oriana, "sub-1"),
            hasher.of(TenantId(1), oriana, "sub-1"),
        )
    }

    @Test
    fun `two tenants never share a reference for the same subject`() {
        val hasher = BrokeredReferenceHasher("instance-secret-key-0123456789012")
        assertNotEquals(
            hasher.of(TenantId(1), oriana, "sub-1"),
            hasher.of(TenantId(2), oriana, "sub-1"),
        )
    }

    @Test
    fun `two providers never share a reference for the same subject`() {
        val hasher = BrokeredReferenceHasher("instance-secret-key-0123456789012")
        assertNotEquals(
            hasher.of(TenantId(1), oriana, "sub-1"),
            hasher.of(TenantId(1), requireNotNull(ProviderKey.of("other-idp")), "sub-1"),
        )
    }

    // The property that makes the reference more than a confirmation oracle: 32 bits over a small,
    // public input is enumerable offline, and only the key stops the enumeration.
    @Test
    fun `a different instance secret gives a different reference for the same identity`() {
        assertNotEquals(
            BrokeredReferenceHasher("instance-secret-key-0123456789012").of(TenantId(1), oriana, "12345"),
            BrokeredReferenceHasher("another-secret-key-9876543210987").of(TenantId(1), oriana, "12345"),
        )
    }

    @Test
    fun `the reference is eight uppercase hex characters`() {
        val reference = BrokeredReferenceHasher("instance-secret-key-0123456789012").of(TenantId(7), oriana, "sub-1")
        assertTrue(reference.matches(Regex("^[0-9A-F]{8}$")), "Unexpected reference shape: $reference")
    }
}
