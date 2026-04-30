package com.kauth.domain.service

import com.kauth.domain.model.BackupExportV1

/**
 * Discriminated union for backup operation results — same shape as [AuthResult],
 * [AdminResult], etc., so route adapters can pattern-match consistently.
 */
sealed class BackupResult<out T> {
    data class Success<T>(
        val value: T,
    ) : BackupResult<T>()

    data class Failure(
        val error: BackupError,
    ) : BackupResult<Nothing>()
}

sealed class BackupError(
    val message: String,
) {
    /** Source tenant slug does not exist on this deployment. */
    class TenantNotFound(
        slug: String,
    ) : BackupError("Tenant '$slug' not found")

    /** Destination slug already exists; new-tenant import requires a free slug. */
    class SlugConflict(
        slug: String,
    ) : BackupError("Tenant slug '$slug' already exists. Choose a different --new-slug.")

    /** Envelope did not decrypt — wrong passphrase or tampered ciphertext. */
    object WrongPassphrase : BackupError("Backup decryption failed — wrong passphrase or corrupt envelope.")

    /** Envelope was structurally invalid before crypto could even run. */
    class MalformedEnvelope(
        details: String,
    ) : BackupError("Backup envelope is malformed: $details")

    /** Envelope is from a future format version that this build does not understand. */
    class UnsupportedExportVersion(
        version: String,
    ) : BackupError(
            "Backup export version '$version' is newer than this build supports. Upgrade Kotauth and retry.",
        )

    /**
     * Schema drift between the source export and this deployment is too large to
     * import safely. The export's schemaVersion is higher than current — meaning
     * the source ran a newer Kotauth with migrations we don't have yet.
     */
    class SchemaTooNew(
        exportSchemaVersion: Int,
        currentSchemaVersion: Int,
    ) : BackupError(
            "Backup was taken at schema V$exportSchemaVersion but this build is at V$currentSchemaVersion. " +
                "Upgrade Kotauth on the destination before importing.",
        )

    /** Decoded JSON did not match the [BackupExportV1] shape. */
    class InvalidPayload(
        details: String,
    ) : BackupError("Backup payload is invalid: $details")

    /**
     * Catch-all for unexpected I/O or persistence failures during export/import.
     * The message is operator-facing — keep it actionable.
     */
    class Internal(
        details: String,
    ) : BackupError(details)
}

/**
 * Result of comparing an export's [BackupExportV1.schemaVersion] against the
 * current deployment's Flyway head.
 *
 * Forward-additive imports (export taken at a lower V-number) are allowed but
 * surface a [WarnAdditive] so the operator sees what they're skipping. Backward
 * imports (export from a newer V-number than we know about) are rejected
 * outright — we cannot safely apply unknown migrations in reverse.
 */
sealed class CompatibilityResult {
    data object Ok : CompatibilityResult()

    /** Export is older than current; safe to import but the operator should know. */
    data class WarnAdditive(
        val exportSchemaVersion: Int,
        val currentSchemaVersion: Int,
    ) : CompatibilityResult()

    /** Export is newer than current; reject and ask the operator to upgrade. */
    data class RejectBreaking(
        val exportSchemaVersion: Int,
        val currentSchemaVersion: Int,
    ) : CompatibilityResult()
}

/**
 * Pure decision module — given the export's schemaVersion and the current
 * deployment's Flyway head, what's the import policy?
 *
 * v1.9.0 policy: same V-number = Ok, lower V-number = WarnAdditive (forward-
 * additive only), higher V-number = RejectBreaking. This is intentionally strict
 * for the first release; once we have one or two real cross-version migration
 * paths we can soften it (e.g., explicit allow-list of known-safe deltas).
 */
object BackupCompatibilityMatrix {
    fun check(
        exportSchemaVersion: Int,
        currentSchemaVersion: Int,
    ): CompatibilityResult =
        when {
            exportSchemaVersion == currentSchemaVersion -> CompatibilityResult.Ok
            exportSchemaVersion < currentSchemaVersion ->
                CompatibilityResult.WarnAdditive(exportSchemaVersion, currentSchemaVersion)
            else -> CompatibilityResult.RejectBreaking(exportSchemaVersion, currentSchemaVersion)
        }
}
