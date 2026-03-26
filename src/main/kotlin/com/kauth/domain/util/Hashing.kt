package com.kauth.domain.util

import java.security.MessageDigest

/** SHA-256 hex digest of a string. Pure JDK — no framework dependency. */
fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
