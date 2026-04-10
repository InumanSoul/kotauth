package com.kauth.cli

import com.kauth.domain.util.SecureTokens
import kotlin.system.exitProcess

object GenerateSecretKeyCommand {
    fun execute() {
        println(SecureTokens.randomHex(32))
        exitProcess(0)
    }
}
