package com.kauth.cli

import kotlin.system.exitProcess

object CliRunner {
    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "generate-secret-key" -> GenerateSecretKeyCommand.execute()
            "reset-admin-mfa" -> ResetAdminMfaCommand.execute(args.drop(1))
            null, "--help", "-h" -> printUsage()
            else -> {
                System.err.println("Unknown CLI command: ${args.first()}")
                printUsage()
                exitProcess(1)
            }
        }
    }

    private fun printUsage() {
        System.err.println(
            """
            Usage: java -jar kauth.jar cli <command>

            Commands:
              generate-secret-key                Generate a KAUTH_SECRET_KEY value
              reset-admin-mfa --username=<name>  Reset MFA for an admin user

            Examples:
              java -jar kauth.jar cli generate-secret-key
              java -jar kauth.jar cli reset-admin-mfa --username=admin
              docker exec kotauth java -jar /app/kauth.jar cli reset-admin-mfa --username=admin
            """.trimIndent(),
        )
    }
}
