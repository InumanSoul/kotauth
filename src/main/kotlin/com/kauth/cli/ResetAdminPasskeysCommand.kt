package com.kauth.cli

import com.kauth.config.EnvironmentConfig
import com.kauth.config.ServiceGraph
import com.kauth.domain.service.WebAuthnResult
import com.kauth.infrastructure.DatabaseFactory
import kotlin.system.exitProcess

object ResetAdminPasskeysCommand {
    fun execute(args: List<String>) {
        val username = parseUsername(args)
        if (username == null) {
            System.err.println("Usage: cli reset-admin-passkeys --username=<admin-username>")
            exitProcess(1)
        }

        val config = EnvironmentConfig.load()
        DatabaseFactory.init(
            url = config.dbUrl,
            user = config.dbUser,
            password = config.dbPassword,
            poolMaxSize = 2,
            poolMinIdle = 1,
        )

        val services = ServiceGraph.create(config)

        val masterTenant = services.tenantRepository.findBySlug("master")
        if (masterTenant == null) {
            System.err.println("Master tenant not found. Has the database been initialized?")
            exitProcess(1)
        }

        val user = services.userRepository.findByUsername(masterTenant.id, username)
        if (user == null) {
            System.err.println("No user '$username' found on the master tenant.")
            exitProcess(1)
        }

        val userId = user.id
        if (userId == null) {
            System.err.println("User found but has no ID (database corruption?)")
            exitProcess(2)
        }

        val count =
            when (val result = services.webAuthnService.adminResetAll(masterTenant.id, userId, actorId = userId)) {
                is WebAuthnResult.Success -> result.value
                is WebAuthnResult.Failure -> {
                    System.err.println("Failed to reset passkeys: ${result.error}")
                    exitProcess(2)
                }
            }

        println("Deleted $count passkey(s) for master/$username")
        println("The user can re-enroll passkeys on their next login.")

        exitProcess(0)
    }

    private fun parseUsername(args: List<String>): String? =
        args.firstNotNullOfOrNull { arg ->
            when {
                arg.startsWith("--username=") -> arg.removePrefix("--username=").takeIf { it.isNotBlank() }
                else -> null
            }
        }
}
