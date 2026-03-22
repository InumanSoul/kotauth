package com.kauth.adapter.web.auth

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
        loginRoutes(
            authService = authService,
            oauthService = oauthService,
            tenantRepository = tenantRepository,
            loginRateLimiter = loginRateLimiter,
            mfaService = mfaService,
            roleRepository = roleRepository,
            identityProviderRepository = identityProviderRepository,
            encryptionService = encryptionService,
        )

        registerRoutes(
            authService = authService,
            tenantRepository = tenantRepository,
            registerRateLimiter = registerRateLimiter,
            identityProviderRepository = identityProviderRepository,
        )

        selfServiceRoutes(
            tenantRepository = tenantRepository,
            selfServiceService = selfServiceService,
            registerRateLimiter = registerRateLimiter,
        )

        mfaRoutes(
            oauthService = oauthService,
            tenantRepository = tenantRepository,
            mfaService = mfaService,
            encryptionService = encryptionService,
        )

        socialLoginRoutes(
            oauthService = oauthService,
            tenantRepository = tenantRepository,
            socialLoginService = socialLoginService,
            identityProviderRepository = identityProviderRepository,
            encryptionService = encryptionService,
            baseUrl = baseUrl,
        )

        oauthProtocolRoutes(
            oauthService = oauthService,
            tenantRepository = tenantRepository,
            identityProviderRepository = identityProviderRepository,
            tokenRateLimiter = tokenRateLimiter,
        )
    }
}
