package com.kauth.fakes

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserConsent
import com.kauth.domain.model.UserId
import com.kauth.domain.port.ConsentRepository
import java.time.Instant

class FakeConsentRepository : ConsentRepository {
    private val rows = linkedMapOf<Triple<Int, Int, Int>, UserConsent>()

    fun clear() = rows.clear()

    override fun find(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): UserConsent? = rows[key(tenantId, userId, applicationId)]

    override fun findByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<UserConsent> = rows.values.filter { it.tenantId == tenantId && it.userId == userId }

    override fun save(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        scopes: Set<String>,
        at: Instant,
    ): UserConsent {
        val k = key(tenantId, userId, applicationId)
        val saved =
            rows[k]?.copy(scopes = scopes, updatedAt = at)
                ?: UserConsent(tenantId, userId, applicationId, scopes, createdAt = at, updatedAt = at)
        rows[k] = saved
        return saved
    }

    override fun delete(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): Boolean = rows.remove(key(tenantId, userId, applicationId)) != null

    private fun key(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ) = Triple(tenantId.value, userId.value, applicationId.value)
}
