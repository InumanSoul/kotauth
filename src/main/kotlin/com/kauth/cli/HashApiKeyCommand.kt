package com.kauth.cli

import com.kauth.domain.util.SecureTokens
import com.kauth.domain.util.sha256Hex
import kotlin.system.exitProcess

/**
 * Generates a fresh API key (or hashes a supplied one) for KAUTH_BOOTSTRAP_API_KEYS.
 *
 *   java -jar kauth.jar cli hash-api-key                 # mint a new random key
 *   java -jar kauth.jar cli hash-api-key --key=<value>   # hash the supplied plaintext
 *
 * Prints `plaintext\nsha256` on stdout when generating, or just the hash when hashing.
 */
object HashApiKeyCommand {
    fun execute(args: List<String>) {
        val keyArg = args.firstOrNull { it.startsWith("--key=") }?.substringAfter("=")
        if (keyArg != null) {
            println(sha256Hex(keyArg))
            exitProcess(0)
        }
        val tenant =
            args.firstOrNull { it.startsWith("--tenant=") }?.substringAfter("=")
                ?: "tenant"
        val plaintext = "kauth_${tenant}_${SecureTokens.randomBase64Url(32)}"
        println("plaintext: $plaintext")
        println("sha256:    ${sha256Hex(plaintext)}")
        exitProcess(0)
    }
}
