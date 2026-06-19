package com.kauth.cli

import kotlin.system.exitProcess

object CliRunner {
    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "generate-secret-key" -> GenerateSecretKeyCommand.execute()
            "reset-admin-mfa" -> ResetAdminMfaCommand.execute(args.drop(1))
            "export-tenant" -> ExportTenantCommand.execute(args.drop(1))
            "import-tenant" -> ImportTenantCommand.execute(args.drop(1))
            "hash-api-key" -> HashApiKeyCommand.execute(args.drop(1))
            "verify-audit-chain" -> VerifyAuditChainCommand.execute(args.drop(1))
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
              export-tenant <slug> ...           Export a tenant to an encrypted backup file
              import-tenant <file> ...           Import an encrypted backup as a new tenant
              hash-api-key [--key=<v>] [--tenant=<slug>]
                                                 Print SHA-256 for KAUTH_BOOTSTRAP_API_KEYS
              verify-audit-chain [--tenant=<slug>] [--from-id=<n>] [--quiet]
                                                 Verify HMAC integrity of the audit log chain

            Examples:
              java -jar kauth.jar cli generate-secret-key
              java -jar kauth.jar cli reset-admin-mfa --username=admin
              KAUTH_BACKUP_PASS=... java -jar kauth.jar cli export-tenant acme \
                --passphrase-env=KAUTH_BACKUP_PASS --out=acme.json.enc
              KAUTH_BACKUP_PASS=... java -jar kauth.jar cli import-tenant acme.json.enc \
                --passphrase-env=KAUTH_BACKUP_PASS --new-slug=acme-staging
              KAUTH_SECRET_KEY=... java -jar kauth.jar cli verify-audit-chain --tenant=acme

            Run any command with no arguments to see its detailed usage.
            """.trimIndent(),
        )
    }
}
