package com.kauth.cli

import com.kauth.config.EnvironmentConfig
import com.kauth.config.ServiceGraph
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import com.kauth.domain.service.BackupResult
import com.kauth.infrastructure.DatabaseFactory
import com.kauth.infrastructure.FlywaySchemaHead
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `kauth.jar cli import-tenant <file> --passphrase-env=VAR --new-slug=<slug>`
 *
 * Imports an encrypted tenant backup as a NEW tenant on this deployment. Refuses
 * to overwrite an existing tenant — slug collisions are reported as a fixable
 * error, not auto-renamed (silent renames make it too easy to lose track of
 * which tenant is which after several import attempts).
 *
 * After a successful import the operator must:
 *   - Regenerate OAuth client secrets for every imported application
 *   - Re-enter social provider client secrets and re-enable each provider
 *   - Reconfigure SMTP password if SMTP is in use
 *   - Have any user with `mfaEnabled=true` re-enroll their MFA device
 */
object ImportTenantCommand {
    fun execute(args: List<String>) {
        val parsed =
            parse(args) ?: run {
                printUsage()
                exitProcess(1)
            }

        val passphrase =
            System.getenv(parsed.passphraseEnv)?.takeIf { it.isNotBlank() }
                ?: run {
                    System.err.println("Env var '${parsed.passphraseEnv}' is empty or unset.")
                    exitProcess(1)
                }

        val envelopePath = Path.of(parsed.file).toAbsolutePath()
        if (!Files.exists(envelopePath)) {
            System.err.println("Backup file not found: $envelopePath")
            exitProcess(1)
        }
        val envelope = Files.readString(envelopePath)

        val config = EnvironmentConfig.load()
        DatabaseFactory.init(
            url = config.dbUrl,
            user = config.dbUser,
            password = config.dbPassword,
            poolMaxSize = 2,
            poolMinIdle = 1,
        )
        val schemaHead = FlywaySchemaHead.read(toDbConfig(config))
        val services = ServiceGraph.create(config)

        val passChars = passphrase.toCharArray()
        val plaintext =
            try {
                when (val r = services.backupEncryptionPort.decrypt(envelope, passChars)) {
                    is BackupDecryptResult.Success -> r.plaintext
                    is BackupDecryptResult.Failure -> {
                        printDecryptError(r.error)
                        exitProcess(2)
                    }
                }
            } finally {
                passChars.fill(' ')
            }

        val export =
            try {
                backupJson.decodeFromString(BackupExportV1.serializer(), plaintext)
            } catch (e: Exception) {
                System.err.println("Backup payload could not be parsed: ${e.message}")
                exitProcess(2)
            }

        when (val result = services.backupImporterService.import(export, parsed.newSlug, schemaHead)) {
            is BackupResult.Success -> {
                val s = result.value
                println(
                    "Imported tenant as '${s.newTenantSlug}' (schema=$schemaHead, export schema=${export.schemaVersion})",
                )
                println(
                    "  users=${s.users} apps=${s.applications} roles=${s.roles} groups=${s.groups} " +
                        "claimMappers=${s.claimMappers} socialProviders=${s.socialProviders} " +
                        "signingKeys=${s.signingKeys} auditEvents=${s.auditEvents}",
                )
                println()
                println("Re-enrollment notes:")
                export.manifest.notes.forEach { println("  - $it") }
                if (export.signingKeys == null) {
                    println(
                        "  - No signing keys were exported. The next server start will provision a fresh RSA " +
                            "key pair for this tenant via KeyProvisioningService.",
                    )
                }
                exitProcess(0)
            }
            is BackupResult.Failure -> {
                System.err.println("Import failed: ${result.error.message}")
                exitProcess(2)
            }
        }
    }

    private fun printDecryptError(error: BackupDecryptionError) {
        when (error) {
            is BackupDecryptionError.WrongPassphrase ->
                System.err.println("Wrong passphrase or corrupt envelope — cannot decrypt this backup.")
            is BackupDecryptionError.MalformedEnvelope ->
                System.err.println("Backup envelope is malformed. The file may be truncated or not a Kotauth backup.")
            is BackupDecryptionError.UnsupportedVersion ->
                System.err.println(
                    "Backup envelope version '${error.version}' is newer than this build supports. " +
                        "Upgrade Kotauth and retry.",
                )
        }
    }

    private fun parse(args: List<String>): Args? {
        if (args.isEmpty() || args[0].startsWith("--")) return null
        val file = args[0]
        val opts = args.drop(1)
        val passEnv = opts.flagValue("--passphrase-env=") ?: return null
        val newSlug = opts.flagValue("--new-slug=") ?: return null
        if (!newSlug.matches(SLUG_REGEX)) {
            System.err.println("--new-slug must match $SLUG_REGEX (lowercase, digits, dashes; 2-50 chars).")
            return null
        }
        return Args(file, passEnv, newSlug)
    }

    private fun List<String>.flagValue(prefix: String): String? =
        firstNotNullOfOrNull { arg ->
            if (arg.startsWith(prefix)) arg.removePrefix(prefix).takeIf { it.isNotBlank() } else null
        }

    private fun toDbConfig(c: EnvironmentConfig) =
        com.kauth.config.DbConfig(
            dbUrl = c.dbUrl,
            dbUser = c.dbUser,
            dbPassword = c.dbPassword,
            dbPoolMaxSize = c.dbPoolMaxSize,
            dbPoolMinIdle = c.dbPoolMinIdle,
        )

    private fun printUsage() {
        System.err.println(
            """
            Usage: cli import-tenant <file>
                       --passphrase-env=ENV_VAR
                       --new-slug=<destination-slug>

            Imports the encrypted backup at <file> as a NEW tenant. Fails if the
            slug already exists. After import, OAuth client secrets, social
            provider secrets, SMTP password, and any MFA enrollments must be
            recreated by the operator (the manifest in the export documents
            every redacted field).

            Example:
              KAUTH_BACKUP_PASS='<long random passphrase>' \
                java -jar kauth.jar cli import-tenant acme.json.enc \
                  --passphrase-env=KAUTH_BACKUP_PASS \
                  --new-slug=acme-staging
            """.trimIndent(),
        )
    }

    private val backupJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,49}$")

    private data class Args(
        val file: String,
        val passphraseEnv: String,
        val newSlug: String,
    )
}
