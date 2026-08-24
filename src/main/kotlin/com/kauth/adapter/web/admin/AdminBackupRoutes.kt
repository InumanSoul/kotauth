package com.kauth.adapter.web.admin

import com.kauth.adapter.web.api.respondProblem
import com.kauth.adapter.web.plugin.MaxRequestBodyBytesAttr
import com.kauth.domain.model.ApiScope
import com.kauth.domain.model.AuditEvent
import com.kauth.domain.model.AuditEventType
import com.kauth.domain.model.BackupExportV1
import com.kauth.domain.model.Tenant
import com.kauth.domain.port.AuditLogPort
import com.kauth.domain.port.BackupDecryptResult
import com.kauth.domain.port.BackupDecryptionError
import com.kauth.domain.port.BackupEncryptionPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.ApiKeyService
import com.kauth.domain.service.BackupError
import com.kauth.domain.service.BackupExporterService
import com.kauth.domain.service.BackupImporterService
import com.kauth.domain.service.BackupResult
import com.kauth.domain.service.ExportOptions
import com.kauth.infrastructure.ApiKeyPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Admin Backup API — JSON endpoints for tenant export/import.
 *
 * Mounted at `/admin/api/v1/tenants/...`. Authentication is bearer API-key
 * scoped to the master tenant; the key must hold [ApiScope.TENANTS_EXPORT] or
 * [ApiScope.TENANTS_IMPORT] depending on the operation.
 *
 * Designed for direct programmatic use AND so the future Kotauth MCP server
 * can wrap each endpoint as a single MCP tool with a 1:1 schema mapping —
 * keep the request/response DTOs flat and well-typed.
 */
fun Route.adminBackupRoutes(
    apiKeyService: ApiKeyService,
    tenantRepository: TenantRepository,
    backupExporterService: BackupExporterService,
    backupImporterService: BackupImporterService,
    backupEncryptionPort: BackupEncryptionPort,
    auditLogPort: AuditLogPort,
    currentSchemaVersion: Int,
    kotauthVersion: String,
    // A full tenant export, base64-encoded, is far larger than any other JSON body this server
    // accepts — the global request-body limit would reject legitimate imports, so this route
    // gets its own higher ceiling instead of raising the limit for every other endpoint.
    maxImportBodyBytes: Long,
) {
    authenticate("api-key") {
        route("/admin/api/v1") {
            post("/tenants/{slug}/export") {
                val masterKey = call.requireMasterAdminKey(apiKeyService, tenantRepository) ?: return@post
                if (ApiScope.TENANTS_EXPORT !in masterKey.scopes) {
                    return@post call.respondProblem(
                        HttpStatusCode.Forbidden,
                        "Insufficient scope",
                        "API key must hold the '${ApiScope.TENANTS_EXPORT}' scope.",
                    )
                }

                val sourceSlug =
                    call.parameters["slug"]
                        ?: return@post call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Missing tenant slug",
                            "The tenant slug path parameter is required.",
                        )

                val request =
                    try {
                        call.receive<ExportRequest>()
                    } catch (e: PayloadTooLargeException) {
                        throw e
                    } catch (e: Exception) {
                        return@post call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid request body",
                            "Body must be a JSON object with 'passphrase'.",
                        )
                    }

                if (request.passphrase.length < MIN_PASSPHRASE_LENGTH) {
                    return@post call.respondProblem(
                        HttpStatusCode.UnprocessableEntity,
                        "Weak passphrase",
                        "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters.",
                    )
                }

                val exportResult =
                    backupExporterService.export(
                        slug = sourceSlug,
                        options =
                            ExportOptions(
                                includeSigningKeys = request.includeSigningKeys,
                                includeAuditLog = request.includeAuditLog,
                            ),
                        kotauthVersion = kotauthVersion,
                        currentSchemaVersion = currentSchemaVersion,
                    )

                val export =
                    when (exportResult) {
                        is BackupResult.Success -> exportResult.value
                        is BackupResult.Failure -> return@post call.respondBackupError(exportResult.error)
                    }

                val plaintext = backupJson.encodeToString(BackupExportV1.serializer(), export)
                val passChars = request.passphrase.toCharArray()
                val envelope =
                    try {
                        backupEncryptionPort.encrypt(plaintext, passChars)
                    } finally {
                        passChars.fill(' ')
                    }

                auditLogPort.record(
                    AuditEvent(
                        tenantId = masterKey.tenantId,
                        userId = null,
                        clientId = null,
                        eventType = AuditEventType.ADMIN_TENANT_EXPORTED,
                        ipAddress = call.request.local.remoteAddress,
                        userAgent = call.request.headers["User-Agent"],
                        details =
                            mapOf(
                                "sourceSlug" to sourceSlug,
                                "schemaVersion" to export.schemaVersion.toString(),
                                "includedSigningKeys" to request.includeSigningKeys.toString(),
                                "includedAuditLog" to request.includeAuditLog.toString(),
                            ),
                    ),
                )

                call.respond(
                    HttpStatusCode.OK,
                    ExportResponse(
                        envelope = envelope,
                        schemaVersion = export.schemaVersion,
                        kotauthVersion = export.kotauthVersion,
                        exportedAt = export.exportedAt,
                        summary =
                            BackupCounts(
                                users = export.users.size,
                                applications = export.applications.size,
                                roles = export.roles.size,
                                groups = export.groups.size,
                                claimMappers = export.claimMappers.size,
                                socialProviders = export.socialProviders.size,
                                signingKeys = export.signingKeys?.size ?: 0,
                                auditEvents = export.auditLog?.size ?: 0,
                            ),
                        manifest =
                            BackupManifestDto(
                                redactedFields = export.manifest.redactedFields,
                                includedExtras = export.manifest.includedExtras,
                                notes = export.manifest.notes,
                            ),
                    ),
                )
            }

            post("/tenants/import") {
                // Must be set before the first call.receive() below — the size-limit plugin
                // reads this override lazily, at the moment the body is actually consumed.
                call.attributes.put(MaxRequestBodyBytesAttr, maxImportBodyBytes)

                val masterKey = call.requireMasterAdminKey(apiKeyService, tenantRepository) ?: return@post
                if (ApiScope.TENANTS_IMPORT !in masterKey.scopes) {
                    return@post call.respondProblem(
                        HttpStatusCode.Forbidden,
                        "Insufficient scope",
                        "API key must hold the '${ApiScope.TENANTS_IMPORT}' scope.",
                    )
                }

                val request =
                    try {
                        call.receive<ImportRequest>()
                    } catch (e: PayloadTooLargeException) {
                        throw e
                    } catch (e: Exception) {
                        return@post call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Invalid request body",
                            "Body must be a JSON object with 'envelope', 'passphrase', and 'newSlug'.",
                        )
                    }

                val passChars = request.passphrase.toCharArray()
                val plaintext =
                    try {
                        when (val r = backupEncryptionPort.decrypt(request.envelope, passChars)) {
                            is BackupDecryptResult.Success -> r.plaintext
                            is BackupDecryptResult.Failure ->
                                return@post call.respondDecryptError(r.error)
                        }
                    } finally {
                        passChars.fill(' ')
                    }

                val export =
                    runCatching { backupJson.decodeFromString(BackupExportV1.serializer(), plaintext) }
                        .getOrElse {
                            return@post call.respondProblem(
                                HttpStatusCode.UnprocessableEntity,
                                "Invalid backup payload",
                                "Decrypted backup body is not a valid v1 export: ${it.message}",
                            )
                        }

                val importResult = backupImporterService.import(export, request.newSlug, currentSchemaVersion)
                when (importResult) {
                    is BackupResult.Failure -> return@post call.respondBackupError(importResult.error)
                    is BackupResult.Success -> {
                        val s = importResult.value
                        auditLogPort.record(
                            AuditEvent(
                                tenantId = masterKey.tenantId,
                                userId = null,
                                clientId = null,
                                eventType = AuditEventType.ADMIN_TENANT_IMPORTED,
                                ipAddress = call.request.local.remoteAddress,
                                userAgent = call.request.headers["User-Agent"],
                                details =
                                    mapOf(
                                        "newSlug" to s.newTenantSlug,
                                        "exportSchemaVersion" to export.schemaVersion.toString(),
                                        "currentSchemaVersion" to currentSchemaVersion.toString(),
                                        "users" to s.users.toString(),
                                        "applications" to s.applications.toString(),
                                    ),
                            ),
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            ImportResponse(
                                newTenantSlug = s.newTenantSlug,
                                schemaVersion = currentSchemaVersion,
                                exportSchemaVersion = export.schemaVersion,
                                summary =
                                    BackupCounts(
                                        users = s.users,
                                        applications = s.applications,
                                        roles = s.roles,
                                        groups = s.groups,
                                        claimMappers = s.claimMappers,
                                        socialProviders = s.socialProviders,
                                        signingKeys = s.signingKeys,
                                        auditEvents = s.auditEvents,
                                    ),
                                manifest =
                                    BackupManifestDto(
                                        redactedFields = export.manifest.redactedFields,
                                        includedExtras = export.manifest.includedExtras,
                                        notes = export.manifest.notes,
                                    ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Validates the bearer principal points to an API key issued in the master
 * tenant. Returns null and writes the appropriate Problem+JSON response on
 * any failure (no API key, key on wrong tenant, key invalid).
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireMasterAdminKey(
    apiKeyService: ApiKeyService,
    tenantRepository: TenantRepository,
): com.kauth.domain.model.ApiKey? {
    val principal =
        principal<ApiKeyPrincipal>()
            ?: run {
                respondProblem(
                    HttpStatusCode.Unauthorized,
                    "Unauthorized",
                    "A master-tenant API key is required. Send Authorization: Bearer kauth_...",
                )
                return null
            }
    val master =
        tenantRepository.findBySlug(Tenant.MASTER_SLUG)
            ?: run {
                respondProblem(
                    HttpStatusCode.InternalServerError,
                    "Master tenant missing",
                    "The master tenant could not be resolved on this deployment.",
                )
                return null
            }
    val key =
        apiKeyService.validate(principal.rawToken, master.id)
            ?: run {
                respondProblem(
                    HttpStatusCode.Unauthorized,
                    "Invalid API key",
                    "The provided API key is not valid for the master tenant.",
                )
                return null
            }
    return key
}

private suspend fun io.ktor.server.application.ApplicationCall.respondBackupError(error: BackupError) {
    val (status, title) =
        when (error) {
            is BackupError.TenantNotFound -> HttpStatusCode.NotFound to "Tenant not found"
            is BackupError.SlugConflict -> HttpStatusCode.Conflict to "Slug conflict"
            is BackupError.WrongPassphrase -> HttpStatusCode.UnprocessableEntity to "Wrong passphrase"
            is BackupError.MalformedEnvelope -> HttpStatusCode.UnprocessableEntity to "Malformed envelope"
            is BackupError.UnsupportedExportVersion ->
                HttpStatusCode.UnprocessableEntity to
                    "Unsupported export version"
            is BackupError.SchemaTooNew -> HttpStatusCode.UnprocessableEntity to "Schema too new"
            is BackupError.InvalidPayload -> HttpStatusCode.UnprocessableEntity to "Invalid payload"
            is BackupError.Internal -> HttpStatusCode.InternalServerError to "Internal error"
        }
    respondProblem(status, title, error.message)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDecryptError(error: BackupDecryptionError) {
    val (status, title, detail) =
        when (error) {
            is BackupDecryptionError.WrongPassphrase ->
                Triple(
                    HttpStatusCode.UnprocessableEntity,
                    "Wrong passphrase",
                    "The supplied passphrase did not decrypt the envelope. Either it is wrong or the file is corrupt.",
                )
            is BackupDecryptionError.MalformedEnvelope ->
                Triple(
                    HttpStatusCode.UnprocessableEntity,
                    "Malformed envelope",
                    "The backup envelope is malformed. The file may be truncated or not a Kotauth backup.",
                )
            is BackupDecryptionError.UnsupportedVersion ->
                Triple(
                    HttpStatusCode.UnprocessableEntity,
                    "Unsupported envelope version",
                    "Backup envelope version '${error.version}' is newer than this build supports. Upgrade Kotauth and retry.",
                )
        }
    respondProblem(status, title, detail)
}

private const val MIN_PASSPHRASE_LENGTH = 16

private val backupJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

@Serializable
data class ExportRequest(
    val passphrase: String,
    val includeSigningKeys: Boolean = false,
    val includeAuditLog: Boolean = false,
)

@Serializable
data class ExportResponse(
    val envelope: String,
    val schemaVersion: Int,
    val kotauthVersion: String,
    val exportedAt: Long,
    val summary: BackupCounts,
    val manifest: BackupManifestDto,
)

@Serializable
data class ImportRequest(
    val envelope: String,
    val passphrase: String,
    val newSlug: String,
)

@Serializable
data class ImportResponse(
    val newTenantSlug: String,
    val schemaVersion: Int,
    val exportSchemaVersion: Int,
    val summary: BackupCounts,
    val manifest: BackupManifestDto,
)

@Serializable
data class BackupCounts(
    val users: Int,
    val applications: Int,
    val roles: Int,
    val groups: Int,
    val claimMappers: Int,
    val socialProviders: Int,
    val signingKeys: Int,
    val auditEvents: Int,
)

@Serializable
data class BackupManifestDto(
    val redactedFields: List<String>,
    val includedExtras: List<String>,
    val notes: List<String>,
)
