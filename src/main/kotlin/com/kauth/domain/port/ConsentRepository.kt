package com.kauth.domain.port

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserConsent
import com.kauth.domain.model.UserId
import java.time.Instant

interface ConsentRepository {
    fun find(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): UserConsent?

    fun findByUser(
        tenantId: TenantId,
        userId: UserId,
    ): List<UserConsent>

    /** Creates the grant, or replaces the scopes of the existing one. */
    fun save(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
        scopes: Set<String>,
        at: Instant,
    ): UserConsent

    /** Returns false when there was nothing to delete. */
    fun delete(
        tenantId: TenantId,
        userId: UserId,
        applicationId: ApplicationId,
    ): Boolean
}
