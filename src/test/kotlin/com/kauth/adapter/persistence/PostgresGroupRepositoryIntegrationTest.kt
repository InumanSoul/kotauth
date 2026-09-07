package com.kauth.adapter.persistence

import com.kauth.domain.model.Group
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.infrastructure.DatabaseFactory
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [PostgresGroupRepository]'s `loadRoles` flag against a real database. The flag decides
 * whether `group_roles` is queried at all, so an empty `roleIds` under `loadRoles = false` means
 * "not loaded", never "this group has no roles" — a distinction the in-memory fake can agree with
 * while the adapter regresses, because only the adapter has a join to skip.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresGroupRepositoryIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val repo = PostgresGroupRepository()
    private val roleRepo = PostgresRoleRepository()

    @BeforeAll
    fun startDb() {
        postgres = PostgreSQLContainer("postgres:15-alpine")
        postgres.start()
        DatabaseFactory.init(
            url = postgres.jdbcUrl,
            user = postgres.username,
            password = postgres.password,
        )
    }

    @AfterAll
    fun stopDb() {
        postgres.stop()
    }

    private fun masterTenantId(): TenantId =
        TenantId(
            transaction {
                TenantsTable
                    .selectAll()
                    .where { TenantsTable.slug eq "master" }
                    .single()[TenantsTable.id]
            },
        )

    @Test
    fun `loadRoles false skips the group_roles join for a group that genuinely has roles`() {
        val tenantId = masterTenantId()
        val group = repo.save(Group(tenantId = tenantId, name = "load-roles-flag-group"))
        val role = roleRepo.save(Role(tenantId = tenantId, name = "load-roles-flag-role", scope = RoleScope.TENANT))
        repo.assignRoleToGroup(group.id!!, role.id!!)

        val loaded = repo.findByTenantId(tenantId, loadRoles = true).single { it.id == group.id }
        val skipped = repo.findByTenantId(tenantId, loadRoles = false).single { it.id == group.id }

        assertEquals(listOf(role.id), loaded.roleIds)
        assertEquals(emptyList(), skipped.roleIds)
        // Every other column still comes back, so a caller that skipped roles has a usable row.
        assertEquals(loaded.name, skipped.name)
        assertEquals(loaded.tenantId, skipped.tenantId)
        assertEquals(loaded.createdAt, skipped.createdAt)
    }

    @Test
    fun `deleting a group that still has a subgroup is refused by the database`() {
        val tenantId = masterTenantId()
        val parent = repo.save(Group(tenantId = tenantId, name = "fk-parent"))
        repo.save(Group(tenantId = tenantId, name = "fk-child", parentGroupId = parent.id))

        // V61's ON DELETE NO ACTION is the backstop behind the service check; without it the old
        // ON DELETE CASCADE would take the child with it and this delete would succeed.
        val threw =
            try {
                repo.delete(parent.id!!)
                false
            } catch (e: Exception) {
                true
            }

        assertTrue(threw, "the foreign key must refuse a parent delete while a subgroup exists")
    }
}
