package com.kauth.domain.service

import com.kauth.domain.model.FactorChange
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.EmailPort
import com.kauth.domain.port.TenantRepository
import com.kauth.domain.port.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Emails a user when one of their sign-in factors changes. Sending happens off the request and
 * never fails the change itself; a workspace without working SMTP sends nothing.
 */
class FactorChangeNotifier(
    private val userRepository: UserRepository,
    private val tenantRepository: TenantRepository,
    private val emailPort: EmailPort,
    private val emailScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(FactorChangeNotifier::class.java)

    fun notify(
        userId: UserId,
        tenantId: TenantId,
        change: FactorChange,
        remainingRecoveryCodes: Int? = null,
    ) {
        val tenant = tenantRepository.findById(tenantId)?.takeIf { it.isSmtpReady } ?: return
        val user = userRepository.findById(userId, tenantId) ?: return
        val occurredAt = Instant.now(clock)
        emailScope.launch {
            try {
                emailPort.sendFactorChangedEmail(
                    to = user.email,
                    toName = user.fullName,
                    change = change,
                    remainingRecoveryCodes = remainingRecoveryCodes,
                    occurredAt = occurredAt,
                    workspaceName = tenant.displayName,
                    tenant = tenant,
                )
            } catch (e: Exception) {
                log.warn(
                    "Factor change email failed tenantId={} userId={} change={}: {}",
                    tenantId.value,
                    userId.value,
                    change,
                    e.message,
                )
            }
        }
    }
}
