package com.kauth

import com.kauth.adapter.web.AppInfo
import com.kauth.adapter.web.admin.AdminSession
import com.kauth.adapter.web.admin.AdminView
import com.kauth.adapter.web.admin.adminRoutes
import com.kauth.adapter.web.api.apiRoutes
import com.kauth.adapter.web.auth.authRoutes
import com.kauth.adapter.web.healthRoutes
import com.kauth.adapter.web.loadAppInfo
import com.kauth.adapter.web.portal.PortalSession
import com.kauth.adapter.web.portal.portalRoutes
import com.kauth.adapter.web.welcomeRoutes
import com.kauth.config.EnvironmentConfig
import com.kauth.config.ServiceGraph
import com.kauth.infrastructure.ApiKeyPrincipal
import com.kauth.infrastructure.DatabaseFactory
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
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
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
    )

    val services = ServiceGraph.create(config)

    val server =
        embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            module(services, appInfo, config, startTime)
        }

    // Background cleanup: purge expired sessions every hour
    services.applicationScope.launch {
        while (isActive) {
            delay(3_600_000) // 1 hour
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

    Runtime.getRuntime().addShutdownHook(
        Thread {
            services.applicationScope.cancel()
            server.stop(
                gracePeriodMillis = 1_000,
                timeoutMillis = 5_000,
            )
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
) {
    // -- Security headers ----------------------------------------------------
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header(
            "Content-Security-Policy",
            "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "font-src 'self' https://fonts.gstatic.com; " +
                "img-src 'self' data: https:; " +
                "form-action 'self'",
        )
        header(HttpHeaders.Server, "KotAuth")
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
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled exception at ${call.request.path()}",
                cause,
            )
            if (call.request.path().startsWith("/admin")) {
                val session = call.sessions.get<AdminSession>()
                val workspaces =
                    try {
                        s.tenantRepository
                            .findAll()
                            .map { it.slug to it.displayName }
                    } catch (_: Exception) {
                        emptyList()
                    }
                call.respondHtml(
                    HttpStatusCode.InternalServerError,
                    AdminView.adminErrorPage(
                        message =
                            cause.message
                                ?: "An unexpected error occurred.",
                        exceptionType = cause::class.qualifiedName,
                        allWorkspaces = workspaces,
                        loggedInAs = session?.username ?: "—",
                    ),
                )
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

        authRoutes(
            authService = s.authService,
            oauthService = s.oauthService,
            tenantRepository = s.tenantRepository,
            loginRateLimiter = s.loginRateLimiter,
            registerRateLimiter = s.registerRateLimiter,
            tokenRateLimiter = s.tokenRateLimiter,
            mfaRateLimiter = s.mfaRateLimiter,
            selfServiceService = s.selfServiceService,
            mfaService = s.mfaService,
            roleRepository = s.roleRepository,
            socialLoginService = s.socialLoginService,
            identityProviderRepository = s.identityProviderRepository,
            baseUrl = config.baseUrl,
            encryptionService = s.encryptionService,
        )

        portalRoutes(
            selfServiceService = s.selfServiceService,
            tenantRepository = s.tenantRepository,
            sessionRepository = s.sessionRepository,
            mfaService = s.mfaService,
            oauthService = s.oauthService,
            baseUrl = config.baseUrl,
            encryptionService = s.encryptionService,
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
            adminService = s.adminService,
        )

        adminRoutes(
            adminService = s.adminService,
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
            selfServiceService = s.selfServiceService,
            roleRepository = s.roleRepository,
            baseUrl = config.baseUrl,
        )
    }
}
