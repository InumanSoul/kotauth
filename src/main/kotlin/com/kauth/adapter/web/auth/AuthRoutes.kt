package com.kauth.adapter.web.auth

import com.kauth.adapter.web.ViewContext
import com.kauth.adapter.web.plugin.TenantCorsPlugin
import com.kauth.adapter.web.plugin.TenantCspPlugin
import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.CorsPort
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.ResourceServerRepository
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.TranslationPort
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.CorsService
import com.kauth.domain.service.CredentialFlowService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.WebAuthnService
import com.kauth.infrastructure.EncryptionService
import com.kauth.infrastructure.InMemoryRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.authRoutes(
    authService: AuthService,
    oauthService: OAuthService,
    tenantRepository: TenantRepository,
    loginRateLimiter: RateLimiterPort,
    registerRateLimiter: RateLimiterPort,
    tokenRateLimiter: RateLimiterPort,
    mfaRateLimiter: RateLimiterPort = InMemoryRateLimiter(maxRequests = 5, windowSeconds = 300),
    credentialFlowService: CredentialFlowService,
    mfaService: MfaService? = null,
    roleRepository: RoleRepository? = null,
    socialLoginService: SocialLoginService? = null,
    identityProviderRepository: IdentityProviderRepository? = null,
    resourceServerRepository: ResourceServerRepository? = null,
    baseUrl: String = "",
    encryptionService: EncryptionService,
    corsService: CorsService? = null,
    corsPort: CorsPort? = null,
    translationPort: TranslationPort,
    ssoTtlSeconds: Long = 86_400L,
    emailOtpService: com.kauth.domain.service.EmailOtpService? = null,
    otpIpRateLimiter: RateLimiterPort? = null,
    webAuthnService: WebAuthnService? = null,
    passkeyRateLimiter: RateLimiterPort? = null,
    socialRateLimiter: RateLimiterPort = InMemoryRateLimiter(maxRequests = 10, windowSeconds = 60),
) {
    route("/t/{slug}") {
        if (corsService != null) {
            install(TenantCorsPlugin) {
                this.corsService = corsService
            }
        }
        if (corsPort != null) {
            install(TenantCspPlugin) {
                this.corsPort = corsPort
            }
        }

        // Resolve tenant context once per request
        val authTenantPlugin =
            createRouteScopedPlugin("AuthTenantPlugin") {
                onCall { call ->
                    val slug =
                        call.parameters["slug"]
                            ?: return@onCall call.respond(HttpStatusCode.BadRequest)
                    val tenant = tenantRepository.findBySlug(slug)
                    val theme = tenant?.theme ?: TenantTheme.DEFAULT
                    val workspaceName = tenant?.displayName ?: "KotAuth"
                    val locale = call.resolveLocale(tenant, translationPort)
                    call.attributes.put(
                        AuthTenantAttr,
                        AuthTenantContext(
                            slug = slug,
                            tenant = tenant,
                            theme = theme,
                            workspaceName = workspaceName,
                            viewContext =
                                ViewContext(
                                    theme = theme,
                                    workspaceName = workspaceName,
                                    locale = locale,
                                    translator = translationPort,
                                ),
                        ),
                    )
                }
            }
        install(authTenantPlugin)

        registerRoutes(
            authService = authService,
            credentialFlowService = credentialFlowService,
            registerRateLimiter = registerRateLimiter,
            identityProviderRepository = identityProviderRepository,
            baseUrl = baseUrl,
            encryptionService = encryptionService,
        )

        selfServiceRoutes(
            credentialFlowService = credentialFlowService,
            registerRateLimiter = registerRateLimiter,
        )

        acceptInviteRoutes(
            credentialFlowService = credentialFlowService,
            rateLimiter = registerRateLimiter,
        )

        forceChangePasswordRoutes(
            credentialFlowService = credentialFlowService,
            rateLimiter = registerRateLimiter,
        )

        magicLinkRoutes(
            credentialFlowService = credentialFlowService,
            rateLimiter = registerRateLimiter,
            encryptionService = encryptionService,
            oauthService = oauthService,
            ssoTtlSeconds = ssoTtlSeconds,
            secure = baseUrl.startsWith("https://", ignoreCase = true),
        )

        if (emailOtpService != null && otpIpRateLimiter != null) {
            emailOtpLoginRoutes(
                emailOtpService = emailOtpService,
                oauthService = oauthService,
                perIpLimiter = otpIpRateLimiter,
                encryptionService = encryptionService,
                ssoTtlSeconds = ssoTtlSeconds,
                secure = baseUrl.startsWith("https://", ignoreCase = true),
            )
        }

        if (webAuthnService != null && passkeyRateLimiter != null) {
            passkeyAuthRoutes(
                webAuthnService = webAuthnService,
                oauthService = oauthService,
                encryptionService = encryptionService,
                rateLimiter = passkeyRateLimiter,
                ssoTtlSeconds = ssoTtlSeconds,
                secure = baseUrl.startsWith("https://", ignoreCase = true),
            )
        }

        mfaRoutes(
            oauthService = oauthService,
            mfaService = mfaService,
            encryptionService = encryptionService,
            mfaRateLimiter = mfaRateLimiter,
            ssoTtlSeconds = ssoTtlSeconds,
            secure = baseUrl.startsWith("https://", ignoreCase = true),
        )

        socialLoginRoutes(
            oauthService = oauthService,
            socialLoginService = socialLoginService,
            identityProviderRepository = identityProviderRepository,
            encryptionService = encryptionService,
            baseUrl = baseUrl,
            ssoTtlSeconds = ssoTtlSeconds,
            socialRateLimiter = socialRateLimiter,
        )

        oauthProtocolRoutes(
            oauthService = oauthService,
            identityProviderRepository = identityProviderRepository,
            tokenRateLimiter = tokenRateLimiter,
            authService = authService,
            mfaService = mfaService,
            roleRepository = roleRepository,
            encryptionService = encryptionService,
            loginRateLimiter = loginRateLimiter,
            resourceServerRepository = resourceServerRepository,
            baseUrl = baseUrl,
            ssoTtlSeconds = ssoTtlSeconds,
        )
    }
}
