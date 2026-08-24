package com.kauth.adapter.web.admin

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.plugin.MaxRequestBodyBytesAttr
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import com.kauth.domain.port.BackupEncryptionPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.BackupError
import com.kauth.domain.service.BackupExporterService
import com.kauth.domain.service.BackupImporterService
import com.kauth.domain.service.BackupResult
import com.kauth.domain.service.ExportOptions
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Per-workspace export route — mounted under the existing
 * `/admin/workspaces/{slug}` block so it inherits the workspace resolver and
 * sidebar/session guards.
 */
fun Route.adminBackupExportRoutes(
    tenantRepository: TenantRepository,
    backupExporterService: BackupExporterService,
    backupEncryptionPort: BackupEncryptionPort,
    appInfo: AppInfo,
    currentSchemaVersion: Int,
) {
    get("/settings/backup") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.workspaceBackupPage(workspace, wsPairs, session.username),
        )
    }

    post("/settings/backup") {
        val session = call.sessions.get<AdminSession>()!!
        val workspace = call.attributes[WorkspaceAttr]
        val wsPairs = call.attributes[WsPairsAttr]
        val params = call.receiveParameters()

        val passphrase = params["passphrase"]?.takeIf { it.isNotBlank() }
        val confirmPassphrase = params["confirmPassphrase"]?.takeIf { it.isNotBlank() }
        val confirmSlug = params["confirmSlug"]?.takeIf { it.isNotBlank() }
        val includeSigningKeys = params["includeSigningKeys"] == "true"
        val includeAuditLog = params["includeAuditLog"] == "true"

        // Validation — keep server-side even though the form does it client-side too
        val validationError =
            when {
                passphrase == null -> "Passphrase is required."
                passphrase.length < 16 -> "Passphrase must be at least 16 characters."
                passphrase != confirmPassphrase -> "Passphrases do not match."
                confirmSlug != workspace.slug ->
                    "Type the workspace slug exactly to confirm — entered '${confirmSlug ?: ""}'."
                else -> null
            }
        if (validationError != null) {
            return@post call.respondHtml(
                HttpStatusCode.UnprocessableEntity,
                AdminView.workspaceBackupPage(workspace, wsPairs, session.username, error = validationError),
            )
        }

        val result =
            backupExporterService.export(
                slug = workspace.slug,
                options =
                    ExportOptions(
                        includeSigningKeys = includeSigningKeys,
                        includeAuditLog = includeAuditLog,
                    ),
                kotauthVersion = appInfo.version,
                currentSchemaVersion = currentSchemaVersion,
            )

        val export =
            when (result) {
                is BackupResult.Success -> result.value
                is BackupResult.Failure ->
                    return@post call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                        AdminView.workspaceBackupPage(
                            workspace,
                            wsPairs,
                            session.username,
                            error = "Export failed: ${result.error.message}",
                        ),
                    )
            }

        val plaintext = backupJson.encodeToString(BackupExportV1.serializer(), export)
        val passChars = passphrase!!.toCharArray()
        val envelope =
            try {
                backupEncryptionPort.encrypt(plaintext, passChars)
            } finally {
                passChars.fill(' ')
            }

        val filename = "${workspace.slug}-${export.exportedAt}.json.enc"
        call.response.headers.append(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
        )
        call.respondText(envelope, ContentType.Application.OctetStream, HttpStatusCode.OK)
    }
}

/**
 * Top-level import route — mounted directly under `/admin/workspaces` (sibling
 * to `/new` and the workspaces list).
 */
fun Route.adminBackupImportRoutes(
    tenantRepository: TenantRepository,
    backupImporterService: BackupImporterService,
    backupEncryptionPort: BackupEncryptionPort,
    currentSchemaVersion: Int,
    // Same reasoning as the JSON API import endpoint (AdminBackupRoutes.kt): an uploaded backup
    // file is an entire tenant export, far larger than any other multipart upload this UI takes.
    maxImportBodyBytes: Long,
) {
    get("/import") {
        val session = call.sessions.get<AdminSession>()!!
        val wsPairs =
            tenantRepository.findAll().map {
                WorkspaceStub(it.slug, it.displayName, it.theme.logoUrl)
            }
        call.respondHtml(
            HttpStatusCode.OK,
            AdminView.importTenantPage(wsPairs, session.username),
        )
    }

    post("/import") {
        // Must be set before receiveMultipart() below — the size-limit plugin reads this
        // override lazily, at the moment the body is actually consumed.
        call.attributes.put(MaxRequestBodyBytesAttr, maxImportBodyBytes)

        val session = call.sessions.get<AdminSession>()!!
        val wsPairs =
            tenantRepository.findAll().map {
                WorkspaceStub(it.slug, it.displayName, it.theme.logoUrl)
            }

        var envelopeText: String? = null
        var passphrase: String? = null
        var newSlug: String? = null

        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "envelope") {
                            envelopeText =
                                withContext(Dispatchers.IO) {
                                    part
                                        .provider()
                                        .toInputStream()
                                        .bufferedReader(Charsets.UTF_8)
                                        .use { it.readText() }
                                }
                        }
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            "passphrase" -> passphrase = part.value
                            "newSlug" -> newSlug = part.value.trim()
                        }
                    }
                    else -> Unit
                }
                part.dispose()
            }
        } catch (e: PayloadTooLargeException) {
            // Must reach StatusPages for the real 413, not the generic "malformed upload" 400
            // below — this is a size condition, not a parse failure.
            throw e
        } catch (e: Exception) {
            return@post call.respondHtml(
                HttpStatusCode.BadRequest,
                AdminView.importTenantPage(wsPairs, session.username, error = "Could not read upload: ${e.message}"),
            )
        }

        val capturedEnvelope = envelopeText
        val capturedPass = passphrase
        val capturedSlug = newSlug

        val validationError =
            when {
                capturedEnvelope.isNullOrBlank() -> "Backup file is required."
                capturedPass.isNullOrBlank() -> "Passphrase is required."
                capturedSlug.isNullOrBlank() -> "New workspace slug is required."
                !capturedSlug.matches(SLUG_REGEX) ->
                    "Slug must be lowercase letters, digits, and dashes; 2–50 characters."
                else -> null
            }
        if (validationError != null) {
            return@post call.respondHtml(
                HttpStatusCode.UnprocessableEntity,
                AdminView.importTenantPage(
                    wsPairs,
                    session.username,
                    error = validationError,
                    prefillSlug = capturedSlug,
                ),
            )
        }

        val passChars = capturedPass!!.toCharArray()
        val plaintext =
            try {
                when (val r = backupEncryptionPort.decrypt(capturedEnvelope!!, passChars)) {
                    is BackupDecryptResult.Success -> r.plaintext
                    is BackupDecryptResult.Failure ->
                        return@post call.respondHtml(
                            HttpStatusCode.UnprocessableEntity,
                            AdminView.importTenantPage(
                                wsPairs,
                                session.username,
                                error = decryptErrorMessage(r.error),
                                prefillSlug = capturedSlug,
                            ),
                        )
                }
            } finally {
                passChars.fill(' ')
            }

        val export =
            try {
                backupJson.decodeFromString(BackupExportV1.serializer(), plaintext)
            } catch (e: Exception) {
                return@post call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.importTenantPage(
                        wsPairs,
                        session.username,
                        error = "Backup file is not a valid v1 export: ${e.message}",
                        prefillSlug = capturedSlug,
                    ),
                )
            }

        when (val result = backupImporterService.import(export, capturedSlug!!, currentSchemaVersion)) {
            is BackupResult.Success -> {
                val flashToken =
                    com.kauth.adapter.web.admin.FlashStore.put(
                        com.kauth.adapter.web.EnglishStrings.TOAST_BACKUP_IMPORTED,
                    )
                call.respondRedirect(
                    "/admin/workspaces/${result.value.newTenantSlug}?flash=$flashToken",
                )
            }
            is BackupResult.Failure ->
                call.respondHtml(
                    HttpStatusCode.UnprocessableEntity,
                    AdminView.importTenantPage(
                        wsPairs,
                        session.username,
                        error = importErrorMessage(result.error),
                        prefillSlug = capturedSlug,
                    ),
                )
        }
    }
}

private fun decryptErrorMessage(error: BackupDecryptionError): String =
    when (error) {
        is BackupDecryptionError.WrongPassphrase ->
            "Wrong passphrase or corrupt envelope — cannot decrypt this backup."
        is BackupDecryptionError.MalformedEnvelope ->
            "Backup file is malformed. The file may be truncated or not a Kotauth backup."
        is BackupDecryptionError.UnsupportedVersion ->
            "Backup envelope version '${error.version}' is newer than this build supports. " +
                "Upgrade Kotauth and retry."
    }

private fun importErrorMessage(error: BackupError): String =
    when (error) {
        is BackupError.SlugConflict -> error.message
        is BackupError.SchemaTooNew -> error.message
        is BackupError.UnsupportedExportVersion -> error.message
        is BackupError.InvalidPayload -> error.message
        is BackupError.WrongPassphrase -> error.message
        is BackupError.MalformedEnvelope -> error.message
        is BackupError.TenantNotFound -> error.message
        is BackupError.Internal -> "Internal error: ${error.message}"
    }

private val backupJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,49}$")
