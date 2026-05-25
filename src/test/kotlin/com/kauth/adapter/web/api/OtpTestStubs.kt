package com.kauth.adapter.web.api

import com.kauth.domain.port.RateLimiterPort
import com.kauth.domain.service.EmailOtpService
import com.kauth.fakes.FakeApplicationRepository
import com.kauth.fakes.FakeAuditLogPort
import com.kauth.fakes.FakeAuthorizationCodeRepository
import com.kauth.fakes.FakeEmailOtpChallengeRepository
import com.kauth.fakes.FakeEmailPort
import com.kauth.fakes.FakeRoleRepository
import com.kauth.fakes.FakeTenantRepository
import com.kauth.fakes.FakeUserRepository

/** Tiny stub bag for tests that exercise apiRoutes() but don't care about OTP. */
internal fun stubEmailOtpService(): EmailOtpService =
    EmailOtpService(
        tenantRepository = FakeTenantRepository(),
        userRepository = FakeUserRepository(),
        challengeRepository = FakeEmailOtpChallengeRepository(),
        applicationRepository = FakeApplicationRepository(),
        authorizationCodeRepository = FakeAuthorizationCodeRepository(),
        emailPort = FakeEmailPort(),
        auditLog = FakeAuditLogPort(),
        roleRepository = FakeRoleRepository(),
    )

internal class AlwaysAllowLimiter : RateLimiterPort {
    override val maxRequests: Int = Int.MAX_VALUE
    override val windowSeconds: Long = 60L

    override fun isAllowed(key: String): Boolean = true

    override fun remaining(key: String): Int = Int.MAX_VALUE

    override fun reset(key: String) = Unit
}
