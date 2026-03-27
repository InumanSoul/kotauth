package com.kauth.adapter.web.auth

import com.kauth.domain.model.TenantTheme
import com.kauth.domain.port.IdentityProviderRepository
import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.port.RoleRepository
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.service.AuthService
import com.kauth.domain.service.MfaService
import com.kauth.domain.service.OAuthService
import com.kauth.domain.service.SocialLoginService
import com.kauth.domain.service.UserSelfServiceService
import com.kauth.infrastructure.EncryptionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
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
    selfServiceService: UserSelfServiceService,
    mfaService: MfaService? = null,
    roleRepository: RoleRepository? = null,
    socialLoginService: SocialLoginService? = null,
    identityProviderRepository: IdentityProviderRepository? = null,
    baseUrl: String = "",
    encryptionService: EncryptionService,
) {
    route("/t/{slug}") {
        // Resolve tenant context once per request
        intercept(ApplicationCallPipeline.Call) {
            val slug =
                call.parameters["slug"]
                    ?: return@intercept call.respond(HttpStatusCode.BadRequest).also { finish() }
            val tenant = tenantRepository.findBySlug(slug)
            call.attributes.put(
                AuthTenantAttr,
                AuthTenantContext(
                    slug = slug,
                    tenant = tenant,
                    theme = tenant?.theme ?: TenantTheme.DEFAULT,
                    workspaceName = tenant?.displayName ?: "KotAuth",
                ),
            )
        }

        registerRoutes(
            authService = authService,
            registerRateLimiter = registerRateLimiter,
            identityProviderRepository = identityProviderRepository,
            baseUrl = baseUrl,
        )

        selfServiceRoutes(
            selfServiceService = selfServiceService,
            registerRateLimiter = registerRateLimiter,
        )

        mfaRoutes(
            oauthService = oauthService,
            mfaService = mfaService,
            encryptionService = encryptionService,
        )

        socialLoginRoutes(
            oauthService = oauthService,
            socialLoginService = socialLoginService,
            identityProviderRepository = identityProviderRepository,
            encryptionService = encryptionService,
            baseUrl = baseUrl,
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
            baseUrl = baseUrl,
        )
    }
}
