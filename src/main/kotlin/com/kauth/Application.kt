package com.kauth

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.UpdateBannerConfig
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.AdminView
import com.kauth.adapter.web.admin.WorkspaceStub
import com.kauth.adapter.web.admin.adminBackupRoutes
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.api.respondProblem
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.adapter.web.healthRoutes
import com.kauth.adapter.web.loadAppInfo
import com.kauth.adapter.web.plugin.buildCspPolicy
import com.kauth.adapter.web.plugin.requestBodySizeLimitPlugin
import com.kauth.adapter.web.portal.PortalSession
import com.kauth.adapter.web.portal.launcherRoutes
import com.kauth.adapter.web.portal.passkeyPortalRoutes
import com.kauth.adapter.web.portal.portalRoutes
import com.kauth.adapter.web.scim.scimAuthError
import com.kauth.adapter.web.versionCheckRoutes
import com.kauth.adapter.web.welcomeRoutes
import com.kauth.config.EnvironmentConfig
import com.kauth.config.ServiceGraph
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.DatabaseFactory
import com.kauth.infrastructure.VersionCheckService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val startupLog = LoggerFactory.getLogger("com.kauth.startup")

/**
 * KotAuth — Composition Root
 *
 * Startup sequence:
 *   1. Validate environment (fail fast on bad config)
 *   2. Run Flyway migrations
 *   3. Wire repositories + services
 *   4. Provision RSA keys for any tenant that lacks one
 *   5. Start Ktor server
 */
fun main(args: Array<String> = emptyArray()) {
    if (args.firstOrNull() == "cli") {
        com.kauth.cli.CliRunner
            .run(args.drop(1))
        return
    }

    val startTime = System.currentTimeMillis()
    val appInfo = loadAppInfo()
    val config = EnvironmentConfig.load()

    DatabaseFactory.init(
        url = config.dbUrl,
        user = config.dbUser,
        password = config.dbPassword,
        poolMaxSize = config.dbPoolMaxSize,
        poolMinIdle = config.dbPoolMinIdle,
        bootstrapAdminPassword = config.bootstrapAdminPassword,
        isDemoMode = config.isDemoMode,
    )

    val services = ServiceGraph.create(config)

    config.bootstrapApiKeysJson?.let { json ->
        runCatching { com.kauth.config.parseBootstrapApiKeyEntries(json) }
            .onFailure { e ->
                startupLog.error("KAUTH_BOOTSTRAP_API_KEYS parse failed: {}", e.message)
                kotlin.system.exitProcess(1)
            }.getOrNull()
            ?.let { entries ->
                when (val result = services.apiKeyBootstrapService.ensureBootstrapped(entries)) {
                    is com.kauth.domain.service.ApiKeyBootstrapService.Result.Failure -> {
                        startupLog.error("KAUTH_BOOTSTRAP_API_KEYS: {}", result.message)
                        kotlin.system.exitProcess(1)
                    }
                    is com.kauth.domain.service.ApiKeyBootstrapService.Result.Provisioned ->
                        result.applied.forEach { o ->
                            startupLog.info(
                                "Bootstrap API key '{}' for tenant '{}' — {}",
                                o.name,
                                o.tenantSlug,
                                o.action.name.lowercase(),
                            )
                        }
                }
            }
    }

    services.redisClientHolder?.let { holder ->
        holder
            .ping(config.redisStartupProbeTimeoutMs)
            .onFailure { e ->
                startupLog.error("Redis startup probe failed: {}", e.message)
                System.err.println(
                    """
                    ┌──────────────────────────────────────────────────────────────┐
                    │  FATAL: KAUTH_REDIS_URL is set but Redis is unreachable.    │
                    │                                                              │
                    │  Refusing to start: rate limiting must be backed by Redis    │
                    │  when configured. Falling back to per-replica limiters       │
                    │  would silently weaken auth-flow protections.                │
                    │                                                              │
                    │  Verify the URL, credentials, and network reachability.      │
                    └──────────────────────────────────────────────────────────────┘
                    """.trimIndent(),
                )
                kotlin.system.exitProcess(1)
            }
        startupLog.info("Redis ready | url={}", config.redisUrl)
    }

    // Background: check for new KotAuth versions every 6 hours
    val versionCheckService =
        VersionCheckService(
            currentVersion = appInfo.version,
            manifestUrl = config.updateCheckUrl,
            enabled = config.updateCheckEnabled,
            scope = services.applicationScope,
        )
    versionCheckService.start()
    UpdateBannerConfig.register(versionCheckService)

    val server =
        embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            module(services, appInfo, config, startTime, versionCheckService)
        }

    // Background cleanup: purge expired sessions every hour
    services.applicationScope.launch {
        while (isActive) {
            delay(1.hours)
            try {
                val deleted = services.sessionRepository.deleteExpired()
                if (deleted > 0) {
                    startupLog.info("Session cleanup: deleted {} expired rows", deleted)
                }
            } catch (e: Exception) {
                startupLog.warn("Session cleanup failed: {}", e.message)
            }
        }
    }

    // Background sweep: retry orphaned webhook deliveries every 5 minutes
    services.applicationScope.launch {
        while (isActive) {
            delay(5.minutes)
            try {
                services.webhookService.retrySweep()
            } catch (e: Exception) {
                startupLog.warn("Webhook recovery sweep failed: {}", e.message)
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            services.applicationScope.cancel()
            server.stop(
                gracePeriodMillis = 1_000,
                timeoutMillis = 5_000,
            )
            services.redisClientHolder?.shutdown()
        },
    )

    startupLog.info(
        "KotAuth v{} started | env={} | baseUrl={} | jvm={}",
        appInfo.version,
        config.env,
        config.baseUrl,
        System.getProperty("java.version"),
    )

    server.start(wait = true)
}

// ---------------------------------------------------------------------------
// Ktor module — plugins + route registration
// ---------------------------------------------------------------------------

fun Application.module(
    s: ServiceGraph,
    appInfo: AppInfo,
    config: EnvironmentConfig,
    startTime: Long,
    versionCheckService: VersionCheckService,
) {
    // Forwarded headers are attacker-controlled unless a trusted reverse proxy
    // strips/sets them. Honoring them on a directly-exposed instance lets
    // clients spoof X-Forwarded-For and bypass every per-IP rate limit.
    // Only enable behind a proxy that overwrites these headers (see ENV_REFERENCE).
    if (config.trustedProxy) {
        install(XForwardedHeaders)
        startupLog.info("KAUTH_TRUSTED_PROXY=true — honoring X-Forwarded-* headers for client IPs")
    } else {
        startupLog.info("Forwarded headers ignored (KAUTH_TRUSTED_PROXY not set) — using socket peer IPs")
    }

    // -- Security headers ----------------------------------------------------
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Content-Security-Policy", buildCspPolicy())
        header(HttpHeaders.Server, "")
        if (config.isHttps) {
            header(
                HttpHeaders.StrictTransportSecurity,
                "max-age=31536000; includeSubDomains",
            )
        }
    }

    // -- Response compression -------------------------------------------------
    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(1024)
        }
        deflate {
            priority = 0.9
            minimumSize(1024)
        }
        excludeContentType(ContentType.Image.Any)
    }

    // -- Cache headers for static assets -------------------------------------
    install(CachingHeaders) {
        options { _, content ->
            val contentType = content.contentType?.withoutParameters()
            when {
                // CSS and JS are cache-busted via ?v= query param per release
                contentType == ContentType.Text.CSS ||
                    contentType == ContentType.Application.JavaScript ->
                    CachingOptions(
                        cacheControl =
                            CacheControl.MaxAge(
                                maxAgeSeconds = 31536000,
                                visibility = CacheControl.Visibility.Public,
                            ),
                    )
                // HTML pages must always revalidate
                contentType == ContentType.Text.Html ->
                    CachingOptions(
                        cacheControl = CacheControl.NoCache(null),
                    )
                else -> null
            }
        }
    }

    // Must install before ContentNegotiation — it needs to see the raw body channel before JSON
    // deserialization consumes it. See RequestBodySizeLimitPlugin.kt for why Content-Length
    // alone cannot enforce this (chunked requests carry none).
    install(requestBodySizeLimitPlugin(config.maxRequestBodyBytes))

    install(ContentNegotiation) { json() }

    // Request ID → MDC for structured logging
    install(CallId) {
        generate {
            java.util.UUID
                .randomUUID()
                .toString()
        }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        mdc("tenantSlug") { call ->
            val path = call.request.path()
            if (path.startsWith("/t/")) path.split("/").getOrNull(2) else null
        }
        filter { call -> !call.request.path().startsWith("/health") }
    }

    // API key bearer auth
    install(Authentication) {
        bearer("api-key") {
            realm = "KotAuth REST API"
            authenticate { tokenCredential ->
                if (tokenCredential.token.startsWith("kauth_")) {
                    ApiKeyPrincipal(rawToken = tokenCredential.token)
                } else {
                    null
                }
            }
        }
    }

    install(Sessions) {
        val secureCookies = config.isHttps
        cookie<AdminSession>("KOTAUTH_ADMIN") {
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.maxAgeInSeconds = 3600 // 1 hour — matches access token TTL
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(s.adminSessionKey))
        }
        cookie<PortalSession>("KOTAUTH_PORTAL") {
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.maxAgeInSeconds = 3600 * 4
            cookie.extensions["SameSite"] = "Lax"
            transform(
                SessionTransportTransformerMessageAuthentication(
                    s.portalSessionKey,
                ),
            )
        }
    }

    // -- Error boundary ------------------------------------------------------
    install(StatusPages) {
        // Kept distinct from the generic Throwable handler below because 413 must use each
        // surface's own error envelope, not the generic Throwable fallback's OAuth-style JSON —
        // sending the wrong shape here is the same class of bug already fixed once for passkey
        // login (see git history: "send publicKey-envelope shape to browser").
        exception<io.ktor.server.plugins.PayloadTooLargeException> { call, cause ->
            val path = call.request.path()
            val detail = cause.message ?: "Request body exceeds the maximum allowed size."
            when {
                path.contains("/scim/v2") -> {
                    val (status, body) = scimAuthError(HttpStatusCode.PayloadTooLarge, detail)
                    call.respond(status, body)
                }
                path.contains("/api/v1") ->
                    call.respondProblem(HttpStatusCode.PayloadTooLarge, "Payload Too Large", detail)
                path.startsWith("/admin") ->
                    call.respondAdminErrorPage(s, HttpStatusCode.PayloadTooLarge)
                // Portal and auth routes (e.g. /t/{slug}/account, /t/{slug}/login) don't share
                // /admin's error-page template, but they are still browsers — an oversized form
                // POST must not render raw JSON in the tab. Checked via Accept, not path, since
                // these routes don't share a common prefix the way /admin, /api/v1 and /scim/v2 do.
                call.request.accept()?.contains("text/html", ignoreCase = true) == true ->
                    call.respondText(
                        genericBrowserErrorHtml("Payload Too Large", detail),
                        ContentType.Text.Html,
                        HttpStatusCode.PayloadTooLarge,
                    )
                else ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf(
                            "error" to "payload_too_large",
                            "error_description" to detail,
                        ),
                    )
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled exception at ${call.request.path()}",
                cause,
            )
            if (call.request.path().startsWith("/admin")) {
                call.respondAdminErrorPage(s, HttpStatusCode.InternalServerError)
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "error" to "server_error",
                        "error_description" to
                            "An unexpected error occurred",
                    ),
                )
            }
        }
    }

    if (config.isDemoMode) {
        com.kauth.adapter.web.DemoConfig.enabled = true
    }

    // -- Routes --------------------------------------------------------------
    routing {
        staticResources("/static", "static")

        welcomeRoutes(
            config.baseUrl,
            appInfo,
            startTime,
            config.isDevelopment,
        )

        healthRoutes(config.baseUrl)
        versionCheckRoutes(versionCheckService)

        authRoutes(
            authService = s.authService,
            oauthService = s.oauthService,
            tenantRepository = s.tenantRepository,
            loginRateLimiter = s.loginRateLimiter,
            registerRateLimiter = s.registerRateLimiter,
            tokenRateLimiter = s.tokenRateLimiter,
            mfaRateLimiter = s.mfaRateLimiter,
            credentialFlowService = s.credentialFlowService,
            mfaService = s.mfaService,
            roleRepository = s.roleRepository,
            socialLoginService = s.socialLoginService,
            identityProviderRepository = s.identityProviderRepository,
            resourceServerRepository = s.resourceServerRepository,
            baseUrl = config.baseUrl,
            encryptionService = s.encryptionService,
            corsService = s.corsService,
            corsPort = s.corsOriginCache,
            translationPort = s.translationPort,
            ssoTtlSeconds = config.ssoSessionTtlSeconds,
            emailOtpService = s.emailOtpService,
            otpIpRateLimiter = s.otpIpRateLimiter,
            webAuthnService = s.webAuthnService,
            passkeyRateLimiter = s.passkeyRateLimiter,
        )

        portalRoutes(
            accountSelfService = s.accountSelfService,
            tenantRepository = s.tenantRepository,
            sessionRepository = s.sessionRepository,
            mfaService = s.mfaService,
            oauthService = s.oauthService,
            socialAccountRepository = s.socialAccountRepository,
            baseUrl = config.baseUrl,
            encryptionService = s.encryptionService,
            translationPort = s.translationPort,
            impersonationService = s.impersonationService,
            webAuthnService = s.webAuthnService,
        )

        passkeyPortalRoutes(
            webAuthnService = s.webAuthnService,
            encryptionService = s.encryptionService,
            tenantRepository = s.tenantRepository,
            userRepository = s.userRepository,
            sessionRepository = s.sessionRepository,
            secure = config.baseUrl.startsWith("https://", ignoreCase = true),
        )

        launcherRoutes(
            launcherService = s.launcherService,
            tenantRepository = s.tenantRepository,
            translationPort = s.translationPort,
            sessionRepository = s.sessionRepository,
        )

        apiRoutes(
            apiKeyService = s.apiKeyService,
            tenantRepository = s.tenantRepository,
            roleRepository = s.roleRepository,
            groupRepository = s.groupRepository,
            applicationRepository = s.applicationRepository,
            sessionRepository = s.sessionRepository,
            auditLogRepository = s.auditLogRepository,
            roleGroupService = s.roleGroupService,
            accountService = s.accountService,
            adminUserService = s.adminUserService,
            mfaService = s.mfaService,
            applicationManagementService = s.applicationManagementService,
            userAttributeService = s.userAttributeService,
            claimMapperService = s.claimMapperService,
            emailOtpService = s.emailOtpService,
            otpEmailRateLimiter = s.otpEmailRateLimiter,
            otpIpRateLimiter = s.otpIpRateLimiter,
            apiWriteRateLimiter = s.apiWriteRateLimiter,
            apiReadRateLimiter = s.apiReadRateLimiter,
            webhookService = s.webhookService,
            resourceServerService = s.resourceServerService,
            webAuthnService = s.webAuthnService,
            webAuthnCredentialRepository = s.webAuthnCredentialRepository,
            corsService = s.corsService,
            transactionRunner = s.transactionRunner,
            userRepository = s.userRepository,
        )

        adminBackupRoutes(
            apiKeyService = s.apiKeyService,
            tenantRepository = s.tenantRepository,
            backupExporterService = s.backupExporterService,
            backupImporterService = s.backupImporterService,
            backupEncryptionPort = s.backupEncryptionPort,
            auditLogPort = s.auditLogPort,
            currentSchemaVersion = s.flywaySchemaVersion,
            kotauthVersion = appInfo.version,
            maxImportBodyBytes = config.maxBackupImportBodyBytes,
        )

        adminRoutes(
            accountService = s.accountService,
            workspaceSettingsService = s.workspaceSettingsService,
            adminUserService = s.adminUserService,
            applicationManagementService = s.applicationManagementService,
            roleGroupService = s.roleGroupService,
            appInfo = appInfo,
            tenantRepository = s.tenantRepository,
            applicationRepository = s.applicationRepository,
            userRepository = s.userRepository,
            sessionRepository = s.sessionRepository,
            auditLogRepository = s.auditLogRepository,
            keyProvisioningService = s.keyProvisioningService,
            mfaRepository = s.mfaRepository,
            portalClientProvisioning = s.portalClientProvisioning,
            identityProviderRepository = s.identityProviderRepository,
            apiKeyService = s.apiKeyService,
            webhookService = s.webhookService,
            encryptionService = s.encryptionService,
            oauthService = s.oauthService,
            accountSelfService = s.accountSelfService,
            roleRepository = s.roleRepository,
            keyRotationService = s.keyRotationService,
            tenantKeyRepository = s.tenantKeyRepository,
            userAttributeService = s.userAttributeService,
            claimMapperService = s.claimMapperService,
            resourceServerService = s.resourceServerService,
            impersonationService = s.impersonationService,
            backupExporterService = s.backupExporterService,
            backupImporterService = s.backupImporterService,
            backupEncryptionPort = s.backupEncryptionPort,
            flywaySchemaVersion = s.flywaySchemaVersion,
            maxImportBodyBytes = config.maxBackupImportBodyBytes,
            corsPort = s.corsOriginCache,
            baseUrl = config.baseUrl,
            translationPort = s.translationPort,
            webAuthnCredentialRepository = s.webAuthnCredentialRepository,
            webAuthnService = s.webAuthnService,
            mfaService = s.mfaService,
            auditLogPort = s.auditLogPort,
            securityMethodsService = s.securityMethodsService,
        )
    }
}

/**
 * Minimal, dependency-free HTML error body for browser-facing surfaces that have no shared
 * error-page template the way `/admin` does (portal, auth login/register/OTP/magic-link) — used
 * only so a browser doesn't render raw JSON for an oversized form POST.
 */
private fun genericBrowserErrorHtml(
    title: String,
    detail: String,
): String {
    val escapedTitle = title.escapeHtml()
    val escapedDetail = detail.escapeHtml()
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head><meta charset="UTF-8"><title>$escapedTitle</title></head>
        <body>
        <h1>$escapedTitle</h1>
        <p>$escapedDetail</p>
        </body>
        </html>
        """.trimIndent()
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/** Shared admin-UI HTML error page, used by every StatusPages handler that answers a `/admin` request. */
private suspend fun io.ktor.server.application.ApplicationCall.respondAdminErrorPage(
    s: ServiceGraph,
    status: HttpStatusCode,
) {
    val session = sessions.get<AdminSession>()
    val workspaces =
        try {
            s.tenantRepository
                .findAll()
                .map { WorkspaceStub(it.slug, it.displayName, it.theme.logoUrl) }
        } catch (_: Exception) {
            emptyList()
        }
    respondHtml(
        status,
        AdminView.adminErrorPage(
            allWorkspaces = workspaces,
            loggedInAs = session?.username ?: "—",
        ),
    )
}
