package com.kauth.adapter.web.admin

import com.kauth.adapter.web.plugin.requestBodySizeLimitPlugin
import com.kauth.domain.model.Tenant
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.service.BackupImporterService
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeGroupRepository
import com.kauth.fakes.FakeIdentityProviderRepository
import com.kauth.fakes.FakePortalConfigRepository
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantClaimMapperRepository
import com.kauth.fakes.FakeTenantEmailBrandingRepository
import com.kauth.fakes.FakeTenantKeyRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeThemeRepository
import com.kauth.fakes.FakeTransactionRunner
import com.kauth.fakes.FakeUserAttributeRepository
import com.kauth.fakes.FakeUserRepository
import com.kauth.infrastructure.Pbkdf2AesGcmBackupEncryption
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression coverage for the defect where an oversized multipart upload on the browser-based
 * tenant-import flow was answered with **400** HTML ("Could not read upload") instead of
 * **413** — `receiveMultipart()` was wrapped in `catch (e: Exception)`, which also caught
 * `PayloadTooLargeException`. This is the one route the size-limit feature correctly raised the
 * ceiling for; it must actually reach that ceiling's 413, not get reclassified as a parse error.
 */
class AdminBackupImportUiRoutesTest {
    private val tenantRepo = FakeTenantRepository()
    private val userRepo = FakeUserRepository()
    private val roleRepo = FakeRoleRepository()
    private val groupRepo = FakeGroupRepository()
    private val claimMappers = FakeTenantClaimMapperRepository()
    private val idps = FakeIdentityProviderRepository()
    private val tenantKeys = FakeTenantKeyRepository()
    private val themes = FakeThemeRepository()
    private val portalConfigs = FakePortalConfigRepository()
    private val attrs = FakeUserAttributeRepository()
    private val auditPort = FakeAuditLogPort()
    private val txRunner = FakeTransactionRunner()
    private val crypto = Pbkdf2AesGcmBackupEncryption()

    private val acme =
        Tenant(
            id = TenantId(1),
            slug = "acme",
            displayName = "Acme Corp",
            issuerUrl = null,
            theme = TenantTheme.DEFAULT,
        )

    @BeforeTest
    fun setup() {
        tenantRepo.clear()
        tenantRepo.add(acme)
    }

    @Test
    fun `multipart import over its ceiling returns 413, not 400 HTML`() =
        testApplication {
            application { installTestApp(maxImportBodyBytes = TEST_IMPORT_MAX_BYTES) }
            val client = createClient { install(HttpCookies) }
            login(client)

            val response =
                client.post("/admin/workspaces/import") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                // Padded well past TEST_IMPORT_MAX_BYTES.
                                append(
                                    "envelope",
                                    "bkp1.".toByteArray() +
                                        ByteArray(TEST_IMPORT_MAX_BYTES.toInt()) { 'A'.code.toByte() },
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"backup.enc\"")
                                    },
                                )
                                append("passphrase", "this-is-a-strong-passphrase")
                                append("newSlug", "acme-staging")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertFalse(response.bodyAsText().contains("Could not read upload"))
        }

    @Test
    fun `multipart import under its ceiling is not rejected for size`() =
        testApplication {
            application { installTestApp(maxImportBodyBytes = TEST_IMPORT_MAX_BYTES) }
            val client = createClient { install(HttpCookies) }
            login(client)

            val response =
                client.post("/admin/workspaces/import") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "envelope",
                                    "bkp1.garbage".toByteArray(),
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"backup.enc\"")
                                    },
                                )
                                append("passphrase", "this-is-a-strong-passphrase")
                                append("newSlug", "acme-staging")
                            },
                        ),
                    )
                }

            // A garbage-but-small envelope fails on decrypt/parse, not on size.
            assertFalse(response.status == HttpStatusCode.PayloadTooLarge)
        }

    private suspend fun login(client: io.ktor.client.HttpClient) {
        client.submitForm(url = "/test-admin-login", formParameters = Parameters.build { })
    }

    private fun io.ktor.server.application.Application.installTestApp(maxImportBodyBytes: Long) {
        install(requestBodySizeLimitPlugin(TEST_GLOBAL_MAX_BYTES))
        install(StatusPages) {
            exception<PayloadTooLargeException> { call, cause ->
                call.respondText(
                    cause.message ?: "Request body exceeds the maximum allowed size.",
                    status = HttpStatusCode.PayloadTooLarge,
                )
            }
        }
        install(Sessions) {
            cookie<AdminSession>("KOTAUTH_ADMIN") {
                transform(SessionTransportTransformerMessageAuthentication(ByteArray(32)))
            }
        }
        routing {
            post("/test-admin-login") {
                call.sessions.set(AdminSession(userId = 1, tenantId = 1, username = "admin"))
                call.respondText("session set")
            }
            route("/admin/workspaces") {
                adminBackupImportRoutes(
                    tenantRepository = tenantRepo,
                    backupImporterService =
                        BackupImporterService(
                            tenantRepository = tenantRepo,
                            userRepository = userRepo,
                            applicationRepository = com.kauth.fakes.FakeApplicationRepository(),
                            roleRepository = roleRepo,
                            groupRepository = groupRepo,
                            claimMapperRepository = claimMappers,
                            identityProviderRepository = idps,
                            tenantKeyRepository = tenantKeys,
                            themeRepository = themes,
                            portalConfigRepository = portalConfigs,
                            userAttributeRepository = attrs,
                            emailBrandingRepository = FakeTenantEmailBrandingRepository(),
                            auditLogPort = auditPort,
                            transactionRunner = txRunner,
                        ),
                    backupEncryptionPort = crypto,
                    currentSchemaVersion = 38,
                    maxImportBodyBytes = maxImportBodyBytes,
                )
            }
        }
    }
}

private const val TEST_GLOBAL_MAX_BYTES = 4_096L
private const val TEST_IMPORT_MAX_BYTES = 50_000L
