package com.kauth.adapter.persistence

import com.kauth.domain.model.Role
import com.kauth.domain.model.RoleScope
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

    override fun findById(id: Int): Role? = transaction {
        RolesTable.selectAll().where { RolesTable.id eq id }
            .firstOrNull()?.toRole()
    }

    override fun findByName(tenantId: Int, name: String, scope: RoleScope, clientId: Int?): Role? = transaction {
        RolesTable.selectAll().where {
            (RolesTable.tenantId eq tenantId) and
            (RolesTable.name eq name) and
            (RolesTable.scope eq scope.value) and
            if (clientId != null) (RolesTable.clientId eq clientId) else (RolesTable.clientId.isNull())
        }.firstOrNull()?.toRole()
    }

    override fun findByTenantId(tenantId: Int): List<Role> = transaction {
        RolesTable.selectAll().where { RolesTable.tenantId eq tenantId }
            .orderBy(RolesTable.name)
            .map { it.toRole() }
    }

    override fun findByClientId(tenantId: Int, clientId: Int): List<Role> = transaction {
        RolesTable.selectAll().where {
            (RolesTable.tenantId eq tenantId) and
            (RolesTable.clientId eq clientId) and
            (RolesTable.scope eq RoleScope.CLIENT.value)
        }.orderBy(RolesTable.name).map { it.toRole() }
    }

    override fun save(role: Role): Role = transaction {
        val id = RolesTable.insert {
            it[tenantId]    = role.tenantId
            it[name]        = role.name
            it[description] = role.description
            it[scope]       = role.scope.value
            it[clientId]    = role.clientId
            it[createdAt]   = OffsetDateTime.now(ZoneOffset.UTC)
        } get RolesTable.id

        role.copy(id = id)
    }

    override fun update(role: Role): Role = transaction {
        RolesTable.update({ RolesTable.id eq role.id!! }) {
            it[name]        = role.name
            it[description] = role.description
        }
        role
    }

    override fun delete(roleId: Int): Unit = transaction {
        // Cascade handles composite_role_mappings, user_roles, group_roles via FK
        RolesTable.deleteWhere { id eq roleId }
    }

    // -------------------------------------------------------------------------
    // Composite role management
    // -------------------------------------------------------------------------

    override fun addChildRole(parentRoleId: Int, childRoleId: Int): Unit = transaction {
        CompositeRoleMappingsTable.insert {
            it[this.parentRoleId] = parentRoleId
            it[this.childRoleId]  = childRoleId
        }
    }

    override fun removeChildRole(parentRoleId: Int, childRoleId: Int): Unit = transaction {
        CompositeRoleMappingsTable.deleteWhere {
            (CompositeRoleMappingsTable.parentRoleId eq parentRoleId) and
            (CompositeRoleMappingsTable.childRoleId eq childRoleId)
        }
    }

    override fun findChildRoleIds(roleId: Int): List<Int> = transaction {
        CompositeRoleMappingsTable.selectAll()
            .where { CompositeRoleMappingsTable.parentRoleId eq roleId }
            .map { it[CompositeRoleMappingsTable.childRoleId] }
    }

    // -------------------------------------------------------------------------
    // User ↔ Role assignment
    // -------------------------------------------------------------------------

    override fun assignRoleToUser(userId: Int, roleId: Int): Unit = transaction {
        UserRolesTable.insert {
            it[this.userId]     = userId
            it[this.roleId]     = roleId
            it[this.assignedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override fun unassignRoleFromUser(userId: Int, roleId: Int): Unit = transaction {
        UserRolesTable.deleteWhere {
            (UserRolesTable.userId eq userId) and (UserRolesTable.roleId eq roleId)
        }
    }

    override fun findRolesForUser(userId: Int): List<Role> = transaction {
        (UserRolesTable innerJoin RolesTable)
            .selectAll().where { UserRolesTable.userId eq userId }
            .map { it.toRole() }
    }

    override fun findUserIdsForRole(roleId: Int): List<Int> = transaction {
        UserRolesTable.selectAll()
            .where { UserRolesTable.roleId eq roleId }
            .map { it[UserRolesTable.userId] }
    }

    // -------------------------------------------------------------------------
    // Effective role resolution (direct + group + composite expansion)
    // -------------------------------------------------------------------------

    override fun resolveEffectiveRoles(userId: Int, tenantId: Int): List<Role> = transaction {
        // Step 1: direct user roles
        val directRoleIds = UserRolesTable.selectAll()
            .where { UserRolesTable.userId eq userId }
            .map { it[UserRolesTable.roleId] }
            .toMutableSet()

        // Step 2: roles from group membership (including ancestor groups)
        val userGroupIds = UserGroupsTable.selectAll()
            .where { UserGroupsTable.userId eq userId }
            .map { it[UserGroupsTable.groupId] }

        val allGroupIds = mutableSetOf<Int>()
        for (gid in userGroupIds) {
            allGroupIds.add(gid)
            allGroupIds.addAll(findAncestorGroupIdsInternal(gid))
        }

        if (allGroupIds.isNotEmpty()) {
            val groupRoleIds = GroupRolesTable.selectAll()
                .where { GroupRolesTable.groupId inList allGroupIds }
                .map { it[GroupRolesTable.roleId] }
            directRoleIds.addAll(groupRoleIds)
        }

        // Step 3: expand composite roles (BFS to prevent cycles)
        val expandedRoleIds = expandCompositeRoles(directRoleIds)

        // Step 4: load all role entities, filter to this tenant
        if (expandedRoleIds.isEmpty()) return@transaction emptyList()

        RolesTable.selectAll()
            .where { (RolesTable.id inList expandedRoleIds) and (RolesTable.tenantId eq tenantId) }
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
            val children = CompositeRoleMappingsTable.selectAll()
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
        var currentId: Int? = GroupsTable.selectAll()
            .where { GroupsTable.id eq groupId }
            .firstOrNull()?.get(GroupsTable.parentGroupId)

        while (currentId != null && visited.add(currentId)) {
            ancestors.add(currentId)
            currentId = GroupsTable.selectAll()
                .where { GroupsTable.id eq currentId!! }
                .firstOrNull()?.get(GroupsTable.parentGroupId)
        }
        return ancestors
    }

    // -------------------------------------------------------------------------
    // Row mapping
    // -------------------------------------------------------------------------

    private fun ResultRow.toRole(): Role {
        val roleId = this[RolesTable.id]
        return Role(
            id          = roleId,
            tenantId    = this[RolesTable.tenantId],
            name        = this[RolesTable.name],
            description = this[RolesTable.description],
            scope       = RoleScope.fromValue(this[RolesTable.scope]),
            clientId    = this[RolesTable.clientId],
            childRoleIds = CompositeRoleMappingsTable.selectAll()
                .where { CompositeRoleMappingsTable.parentRoleId eq roleId }
                .map { it[CompositeRoleMappingsTable.childRoleId] },
            createdAt   = this[RolesTable.createdAt].toInstant()
        )
    }
}
