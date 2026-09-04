package com.kauth.domain.service

import com.kauth.adapter.token.JavaJwtVerifierAdapter
import com.kauth.domain.service.OidcTokenFailure.Reason
import com.kauth.fakes.FakeOidcIssuer
import com.kauth.fakes.FakeOidcIssuer.Signing
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [OidcTokenValidator].
 *
 * [FakeOidcIssuer] mints tokens with real signatures over a test key pair and publishes the keys
 * that verify them, so the whole chain — header, allowlist, key, signature, claim policy — runs
 * offline. The real [JavaJwtVerifierAdapter] is used rather than a fake: a fake verifier would
 * verify nothing, and the algorithm-confusion case would then prove nothing either.
 */
class OidcTokenValidatorTest {
    private val now = Instant.parse("2026-08-25T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val issuer = FakeOidcIssuer(clock = clock)
    private val validator = OidcTokenValidator(issuer, JavaJwtVerifierAdapter(), clock)

    private val clientId = FakeOidcIssuer.DEFAULT_AUDIENCE
    private val nonce = FakeOidcIssuer.DEFAULT_NONCE

    private fun validate(token: String) = validator.validate(token, issuer.issuer, clientId, nonce, issuer.jwksUri)

    private fun reasonOf(result: Result<OidcClaims>): Reason {
        assertNull(result.getOrNull(), "expected a failure but the token validated")
        return assertIs<OidcTokenFailure>(result.exceptionOrNull()).reason
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    // -- the happy path -----------------------------------------------------

    @Test
    fun `a well formed token validates and maps every profile claim`() {
        val token =
            issuer.idToken(
                subject = "google-oauth2|1234",
                profile =
                    mapOf(
                        "email" to "ada@example.com",
                        "email_verified" to true,
                        "name" to "Ada Lovelace",
                        "given_name" to "Ada",
                        "family_name" to "Lovelace",
                        "picture" to "https://cdn.example/ada.png",
                    ),
            )

        val claims = validate(token).getOrThrow()

        assertEquals("google-oauth2|1234", claims.subject)
        assertEquals("ada@example.com", claims.email)
        assertTrue(claims.emailVerified)
        assertEquals("Ada Lovelace", claims.name)
        assertEquals("Ada", claims.givenName)
        assertEquals("Lovelace", claims.familyName)
        assertEquals("https://cdn.example/ada.png", claims.picture)
    }

    @Test
    fun `a token carrying only sub validates with the profile left empty`() {
        val claims = validate(issuer.idToken()).getOrThrow()

        assertEquals(FakeOidcIssuer.DEFAULT_SUBJECT, claims.subject)
        assertNull(claims.email)
        assertEquals(false, claims.emailVerified)
        assertNull(claims.name)
    }

    @Test
    fun `the identity is sub, never email`() {
        val token = issuer.idToken(subject = "stable-opaque-id", profile = mapOf("email" to "reused@example.com"))

        val claims = validate(token).getOrThrow()

        assertEquals("stable-opaque-id", claims.subject)
    }

    @Test
    fun `an ES256 token validates against the issuer's EC key`() {
        val token = issuer.idToken(signedWith = Signing.OWN_EC_KEY, keyId = issuer.ecKeyId)

        assertEquals(FakeOidcIssuer.DEFAULT_SUBJECT, validate(token).getOrThrow().subject)
    }

    // -- algorithm allowlist, checked before any key is fetched -------------

    @Test
    fun `an HS256 token signed with the issuer's public key is refused before any key is fetched`() {
        val token = issuer.idToken(signedWith = Signing.PUBLIC_KEY_AS_HMAC_SECRET)

        val result = validate(token)

        assertEquals(Reason.UNSUPPORTED_ALGORITHM, reasonOf(result))
        assertEquals(emptyList(), issuer.keyLookups)
    }

    @Test
    fun `an unsigned token is refused before any key is fetched`() {
        val token = issuer.idToken(signedWith = Signing.NONE)

        val result = validate(token)

        assertEquals(Reason.UNSUPPORTED_ALGORITHM, reasonOf(result))
        assertEquals(emptyList(), issuer.keyLookups)
    }

    // -- key and signature --------------------------------------------------

    @Test
    fun `a kid the issuer does not publish is refused`() {
        val token = issuer.idToken(keyId = "rotated-away")

        assertEquals(Reason.KEY_UNAVAILABLE, reasonOf(validate(token)))
    }

    @Test
    fun `a token with no kid is refused`() {
        val token = issuer.idToken(keyId = null)

        val result = validate(token)

        assertEquals(Reason.MISSING_KEY_ID, reasonOf(result))
        assertEquals(emptyList(), issuer.keyLookups)
    }

    @Test
    fun `a token signed with a key the issuer does not publish is refused`() {
        val token = issuer.idToken(signedWith = Signing.UNPUBLISHED_KEY)

        assertEquals(Reason.SIGNATURE_INVALID, reasonOf(validate(token)))
    }

    @Test
    fun `a key of the wrong type for the token's algorithm is named apart from a forgery`() {
        // RS256 in the header, but the kid names the issuer's EC key. Folding this into
        // SIGNATURE_INVALID leaves a misconfigured provider and someone forging tokens at us
        // looking identical in the operator's log.
        val token = issuer.idToken(signedWith = Signing.OWN_RSA_KEY, keyId = issuer.ecKeyId)

        assertEquals(Reason.KEY_UNUSABLE, reasonOf(validate(token)))
    }

    @Test
    fun `a token whose payload was edited after signing is refused`() {
        val parts = issuer.idToken().split(".")
        val forgedPayload =
            """{"iss":"${issuer.issuer}","sub":"attacker","aud":"$clientId","nonce":"$nonce",""" +
                """"exp":${now.plusSeconds(300).epochSecond},"iat":${now.epochSecond}}"""
        val tampered = "${parts[0]}.${base64Url(forgedPayload)}.${parts[2]}"

        assertEquals(Reason.SIGNATURE_INVALID, reasonOf(validate(tampered)))
    }

    @Test
    fun `a string that is not a JWT is refused`() {
        assertEquals(Reason.MALFORMED, reasonOf(validate("not-a-token")))
    }

    // -- issuer -------------------------------------------------------------

    @Test
    fun `a token from another issuer is refused`() {
        val token = issuer.idToken(issuer = "https://evil.example")

        assertEquals(Reason.ISSUER_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a token with no iss is refused`() {
        val token = issuer.idToken(issuer = null)

        assertEquals(Reason.ISSUER_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `an issuer differing only by a trailing slash is refused`() {
        val token = issuer.idToken(issuer = issuer.issuer + "/")

        assertEquals(Reason.ISSUER_MISMATCH, reasonOf(validate(token)))
    }

    // -- audience and authorized party --------------------------------------

    @Test
    fun `an audience without our client id is refused`() {
        val token = issuer.idToken(audience = listOf("someone-elses-client"))

        assertEquals(Reason.AUDIENCE_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a token with no aud is refused`() {
        val token = issuer.idToken(audience = emptyList())

        assertEquals(Reason.AUDIENCE_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a multi audience token containing our client id and naming us as azp validates`() {
        val token = issuer.idToken(audience = listOf(clientId, "another-client"), authorizedParty = clientId)

        assertEquals(FakeOidcIssuer.DEFAULT_SUBJECT, validate(token).getOrThrow().subject)
    }

    @Test
    fun `a multi audience token whose azp is another client is refused`() {
        val token = issuer.idToken(audience = listOf(clientId, "another-client"), authorizedParty = "another-client")

        assertEquals(Reason.AUTHORIZED_PARTY_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a multi audience token with no azp is refused`() {
        val token = issuer.idToken(audience = listOf(clientId, "another-client"))

        assertEquals(Reason.AUTHORIZED_PARTY_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a single audience token whose azp is another client is refused`() {
        val token = issuer.idToken(authorizedParty = "another-client")

        assertEquals(Reason.AUTHORIZED_PARTY_MISMATCH, reasonOf(validate(token)))
    }

    // -- expiry and issuance, with 60 s of skew -----------------------------

    @Test
    fun `an expired token is refused`() {
        val token = issuer.idToken(expiresAt = now.minusSeconds(61))

        assertEquals(Reason.EXPIRED, reasonOf(validate(token)))
    }

    @Test
    fun `a token that expired within the skew allowance is accepted`() {
        val token = issuer.idToken(expiresAt = now.minusSeconds(59))

        assertEquals(FakeOidcIssuer.DEFAULT_SUBJECT, validate(token).getOrThrow().subject)
    }

    @Test
    fun `a token with no exp is refused`() {
        val token = issuer.idToken(expiresAt = null)

        assertEquals(Reason.MALFORMED, reasonOf(validate(token)))
    }

    @Test
    fun `a token issued beyond the skew allowance in the future is refused`() {
        val token = issuer.idToken(issuedAt = now.plusSeconds(61))

        assertEquals(Reason.ISSUED_IN_THE_FUTURE, reasonOf(validate(token)))
    }

    @Test
    fun `a token issued within the skew allowance in the future is accepted`() {
        val token = issuer.idToken(issuedAt = now.plusSeconds(59))

        assertEquals(FakeOidcIssuer.DEFAULT_SUBJECT, validate(token).getOrThrow().subject)
    }

    @Test
    fun `a token with no iat is refused`() {
        val token = issuer.idToken(issuedAt = null)

        assertEquals(Reason.MALFORMED, reasonOf(validate(token)))
    }

    // -- nonce --------------------------------------------------------------

    @Test
    fun `a nonce that is not the one we sent is refused`() {
        val token = issuer.idToken(nonce = "nonce-from-someone-elses-session")

        assertEquals(Reason.NONCE_MISMATCH, reasonOf(validate(token)))
    }

    @Test
    fun `a token with no nonce is refused`() {
        val token = issuer.idToken(nonce = null)

        assertEquals(Reason.NONCE_MISMATCH, reasonOf(validate(token)))
    }

    // -- subject ------------------------------------------------------------

    @Test
    fun `a token with no sub is refused`() {
        val token = issuer.idToken(subject = null, profile = mapOf("email" to "ada@example.com"))

        assertEquals(Reason.SUBJECT_MISSING, reasonOf(validate(token)))
    }

    @Test
    fun `a token whose sub is blank is refused`() {
        val token = issuer.idToken(subject = "   ")

        assertEquals(Reason.SUBJECT_MISSING, reasonOf(validate(token)))
    }
}
