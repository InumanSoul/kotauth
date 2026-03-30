package com.kauth.cli

import java.security.SecureRandom
import kotlin.system.exitProcess

object GenerateSecretKeyCommand {
    fun execute() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        println(bytes.joinToString("") { "%02x".format(it) })
        exitProcess(0)
    }
}
