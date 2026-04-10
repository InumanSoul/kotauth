package com.kauth.domain.util

import java.security.SecureRandom
import java.util.Base64

/** Shared [SecureRandom] instance for cryptographic random generation. Thread-safe. */
object SecureTokens {
    private val random = SecureRandom()

    /** Returns [n] cryptographically strong random bytes. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Returns a URL-safe base64-encoded random token. */
    fun randomBase64Url(byteLength: Int = 32): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(byteLength))

    /** Returns a hex-encoded random string. */
    fun randomHex(byteLength: Int = 32): String = randomBytes(byteLength).joinToString("") { "%02x".format(it) }
}
