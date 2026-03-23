package com.kauth.adapter.persistence

import com.kauth.domain.model.ApplicationId
import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.RoleScope
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.RoleRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres implementation of [RoleRepository].
 *
 * Handles role CRUD, composite mappings, user assignments, and the critical
 * [resolveEffectiveRoles] query that aggregates direct + group + composite roles
 * for token claim generation.
 */
class PostgresRoleRepository : RoleRepository {
    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    override fun findById(id: RoleId): Role? =
        transaction {
            RolesTable
                .selectAll()
                .where { RolesTable.id eq id.value }
                .firstOrNull()
                ?.toRole()
        }

    override fun findByName(
        tenantId: TenantId,
        name: String,
        scope: RoleScope,
        clientId: ApplicationId?,
    ): Role? =
        transaction {
            RolesTable
                .selectAll()
                .where {
                    (RolesTable.tenantId eq tenantId.value) and
                        (RolesTable.name eq name) and
                        (RolesTable.scope eq scope.value) and
                        if (clientId !=
                            null
                        ) {
                            (RolesTable.clientId eq clientId.value)
                        } else {
                            (RolesTable.clientId.isNull())
                        }
                }.firstOrNull()
                ?.toRole()
        }

    override fun findByTenantId(tenantId: TenantId): List<Role> =
        transaction {
            RolesTable
                .selectAll()
                .where { RolesTable.tenantId eq tenantId.value }
                .orderBy(RolesTable.name)
                .map { it.toRole() }
        }

    override fun findByClientId(
        tenantId: TenantId,
        clientId: ApplicationId,
    ): List<Role> =
        transaction {
            RolesTable
                .selectAll()
                .where {
                    (RolesTable.tenantId eq tenantId.value) and
                        (RolesTable.clientId eq clientId.value) and
                        (RolesTable.scope eq RoleScope.CLIENT.value)
                }.orderBy(RolesTable.name)
                .map { it.toRole() }
        }

    override fun save(role: Role): Role =
        transaction {
            val id =
                RolesTable.insert {
                    it[tenantId] = role.tenantId.value
                    it[name] = role.name
                    it[description] = role.description
                    it[scope] = role.scope.value
                    it[clientId] = role.clientId?.value
                    it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                } get RolesTable.id

            role.copy(id = RoleId(id))
        }

    override fun update(role: Role): Role =
        transaction {
            RolesTable.update({ RolesTable.id eq role.id!!.value }) {
                it[name] = role.name
                it[description] = role.description
            }
            role
        }

    override fun delete(roleId: RoleId): Unit =
        transaction {
            // Cascade handles composite_role_mappings, user_roles, group_roles via FK
            RolesTable.deleteWhere { id eq roleId.value }
        }

    // -------------------------------------------------------------------------
    // Composite role management
    // -------------------------------------------------------------------------

    override fun addChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    ): Unit =
        transaction {
            CompositeRoleMappingsTable.insert {
                it[this.parentRoleId] = parentRoleId.value
                it[this.childRoleId] = childRoleId.value
            }
        }

    override fun removeChildRole(
        parentRoleId: RoleId,
        childRoleId: RoleId,
    ): Unit =
        transaction {
            CompositeRoleMappingsTable.deleteWhere {
                (CompositeRoleMappingsTable.parentRoleId eq parentRoleId.value) and
                    (CompositeRoleMappingsTable.childRoleId eq childRoleId.value)
            }
        }

    override fun findChildRoleIds(roleId: RoleId): List<RoleId> =
        transaction {
            CompositeRoleMappingsTable
                .selectAll()
                .where { CompositeRoleMappingsTable.parentRoleId eq roleId.value }
                .map { RoleId(it[CompositeRoleMappingsTable.childRoleId]) }
        }

    // -------------------------------------------------------------------------
    // User ↔ Role assignment
    // -------------------------------------------------------------------------

    override fun assignRoleToUser(
        userId: UserId,
        roleId: RoleId,
    ): Unit =
        transaction {
            UserRolesTable.insert {
                it[this.userId] = userId.value
                it[this.roleId] = roleId.value
                it[this.assignedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }

    override fun unassignRoleFromUser(
        userId: UserId,
        roleId: RoleId,
    ): Unit =
        transaction {
            UserRolesTable.deleteWhere {
                (UserRolesTable.userId eq userId.value) and (UserRolesTable.roleId eq roleId.value)
            }
        }

    override fun findRolesForUser(userId: UserId): List<Role> =
        transaction {
            (UserRolesTable innerJoin RolesTable)
                .selectAll()
                .where { UserRolesTable.userId eq userId.value }
                .map { it.toRole() }
        }

    override fun findUserIdsForRole(roleId: RoleId): List<UserId> =
        transaction {
            UserRolesTable
                .selectAll()
                .where { UserRolesTable.roleId eq roleId.value }
                .map { UserId(it[UserRolesTable.userId]) }
        }

    // -------------------------------------------------------------------------
    // Effective role resolution (direct + group + composite expansion)
    // -------------------------------------------------------------------------

    override fun resolveEffectiveRoles(
        userId: UserId,
        tenantId: TenantId,
    ): List<Role> =
        transaction {
            // Step 1: direct user roles
            val directRoleIds =
                UserRolesTable
                    .selectAll()
                    .where { UserRolesTable.userId eq userId.value }
                    .map { it[UserRolesTable.roleId] }
                    .toMutableSet()

            // Step 2: roles from group membership (including ancestor groups)
            val userGroupIds =
                UserGroupsTable
                    .selectAll()
                    .where { UserGroupsTable.userId eq userId.value }
                    .map { it[UserGroupsTable.groupId] }

            val allGroupIds = mutableSetOf<Int>()
            for (gid in userGroupIds) {
                allGroupIds.add(gid)
                allGroupIds.addAll(findAncestorGroupIdsInternal(gid))
            }

            if (allGroupIds.isNotEmpty()) {
                val groupRoleIds =
                    GroupRolesTable
                        .selectAll()
                        .where { GroupRolesTable.groupId inList allGroupIds }
                        .map { it[GroupRolesTable.roleId] }
                directRoleIds.addAll(groupRoleIds)
            }

            // Step 3: expand composite roles (BFS to prevent cycles)
            val expandedRoleIds = expandCompositeRoles(directRoleIds)

            // Step 4: load all role entities, filter to this tenant
            if (expandedRoleIds.isEmpty()) return@transaction emptyList()

            RolesTable
                .selectAll()
                .where { (RolesTable.id inList expandedRoleIds) and (RolesTable.tenantId eq tenantId.value) }
                .map { it.toRole() }
        }

    /**
     * BFS expansion of composite roles. Visited set prevents infinite cycles.
     */
    private fun expandCompositeRoles(seedRoleIds: Set<Int>): Set<Int> {
        val visited = seedRoleIds.toMutableSet()
        val queue = ArrayDeque(seedRoleIds)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children =
                CompositeRoleMappingsTable
                    .selectAll()
                    .where { CompositeRoleMappingsTable.parentRoleId eq current }
                    .map { it[CompositeRoleMappingsTable.childRoleId] }

            for (childId in children) {
                if (visited.add(childId)) {
                    queue.addLast(childId)
                }
            }
        }
        return visited
    }

    /**
     * Walks up the group hierarchy to find all ancestor group IDs.
     */
    private fun findAncestorGroupIdsInternal(groupId: Int): List<Int> {
        val ancestors = mutableListOf<Int>()
        val visited = mutableSetOf(groupId)
        var currentId: Int? =
            GroupsTable
                .selectAll()
                .where { GroupsTable.id eq groupId }
                .firstOrNull()
                ?.get(GroupsTable.parentGroupId)

        while (currentId != null && visited.add(currentId)) {
            ancestors.add(currentId)
            currentId =
                GroupsTable
                    .selectAll()
                    .where { GroupsTable.id eq currentId!! }
                    .firstOrNull()
                    ?.get(GroupsTable.parentGroupId)
        }
        return ancestors
    }

    // -------------------------------------------------------------------------
    // Row mapping
    // -------------------------------------------------------------------------

    private fun ResultRow.toRole(): Role {
        val roleId = this[RolesTable.id]
        return Role(
            id = RoleId(roleId),
            tenantId = TenantId(this[RolesTable.tenantId]),
            name = this[RolesTable.name],
            description = this[RolesTable.description],
            scope = RoleScope.fromValue(this[RolesTable.scope]),
            clientId = this[RolesTable.clientId]?.let { ApplicationId(it) },
            childRoleIds =
                CompositeRoleMappingsTable
                    .selectAll()
                    .where { CompositeRoleMappingsTable.parentRoleId eq roleId }
                    .map { RoleId(it[CompositeRoleMappingsTable.childRoleId]) },
            createdAt = this[RolesTable.createdAt].toInstant(),
        )
    }
}
