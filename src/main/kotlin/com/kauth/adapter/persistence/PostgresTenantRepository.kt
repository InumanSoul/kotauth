package com.kauth.adapter.persistence

import com.kauth.domain.model.Tenant
import com.kauth.domain.port.TenantRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Persistence adapter — implements TenantRepository using PostgreSQL + Exposed.
 *
 * Tenant lookups are frequent (every auth request) and read-heavy.
 * A simple in-process cache keyed by slug would be a worthwhile optimisation
 * once traffic warrants it — the port interface makes that swap transparent.
 */
class PostgresTenantRepository : TenantRepository {

    override fun findBySlug(slug: String): Tenant? = transaction {
        TenantsTable.selectAll()
            .where { TenantsTable.slug eq slug }
            .map { it.toTenant() }
            .singleOrNull()
    }

    override fun existsBySlug(slug: String): Boolean = transaction {
        TenantsTable.selectAll()
            .where { TenantsTable.slug eq slug }
            .count() > 0
    }

    override fun findAll(): List<Tenant> = transaction {
        TenantsTable.selectAll()
            .orderBy(TenantsTable.id)
            .map { it.toTenant() }
    }

    override fun create(slug: String, displayName: String, issuerUrl: String?): Tenant = transaction {
        val insertedId = TenantsTable.insert {
            it[TenantsTable.slug] = slug
            it[TenantsTable.displayName] = displayName
            it[TenantsTable.issuerUrl] = issuerUrl
        } get TenantsTable.id

        TenantsTable.selectAll()
            .where { TenantsTable.id eq insertedId }
            .single()
            .toTenant()
    }

    private fun ResultRow.toTenant(): Tenant = Tenant(
        id                           = this[TenantsTable.id],
        slug                         = this[TenantsTable.slug],
        displayName                  = this[TenantsTable.displayName],
        issuerUrl                    = this[TenantsTable.issuerUrl],
        tokenExpirySeconds           = this[TenantsTable.tokenExpirySeconds].toLong(),
        refreshTokenExpirySeconds    = this[TenantsTable.refreshTokenExpirySeconds].toLong(),
        registrationEnabled          = this[TenantsTable.registrationEnabled],
        emailVerificationRequired    = this[TenantsTable.emailVerificationRequired],
        passwordPolicyMinLength      = this[TenantsTable.passwordPolicyMinLength],
        passwordPolicyRequireSpecial = this[TenantsTable.passwordPolicyRequireSpecial]
    )
}
