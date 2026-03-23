package com.kauth.adapter.persistence

import com.kauth.domain.model.Group
import com.kauth.domain.model.GroupId
import com.kauth.domain.model.RoleId
import com.kauth.domain.model.TenantId
import com.kauth.domain.model.UserId
import com.kauth.domain.port.GroupRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres implementation of [GroupRepository].
 */
class PostgresGroupRepository : GroupRepository {
    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    override fun findById(id: GroupId): Group? =
        transaction {
            GroupsTable
                .selectAll()
                .where { GroupsTable.id eq id.value }
                .firstOrNull()
                ?.toGroup()
        }

    override fun findByTenantId(tenantId: TenantId): List<Group> =
        transaction {
            GroupsTable
                .selectAll()
                .where { GroupsTable.tenantId eq tenantId.value }
                .orderBy(GroupsTable.name)
                .map { it.toGroup() }
        }

    override fun findByName(
        tenantId: TenantId,
        name: String,
        parentGroupId: GroupId?,
    ): Group? =
        transaction {
            GroupsTable
                .selectAll()
                .where {
                    (GroupsTable.tenantId eq tenantId.value) and
                        (GroupsTable.name eq name) and
                        if (parentGroupId != null) {
                            (GroupsTable.parentGroupId eq parentGroupId.value)
                        } else {
                            (GroupsTable.parentGroupId.isNull())
                        }
                }.firstOrNull()
                ?.toGroup()
        }

    override fun findChildren(groupId: GroupId): List<Group> =
        transaction {
            GroupsTable
                .selectAll()
                .where { GroupsTable.parentGroupId eq groupId.value }
                .orderBy(GroupsTable.name)
                .map { it.toGroup() }
        }

    override fun save(group: Group): Group =
        transaction {
            val id =
                GroupsTable.insert {
                    it[tenantId] = group.tenantId.value
                    it[name] = group.name
                    it[description] = group.description
                    it[parentGroupId] = group.parentGroupId?.value
                    it[attributes] = serializeAttributes(group.attributes)
                    it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                } get GroupsTable.id

            group.copy(id = GroupId(id))
        }

    override fun update(group: Group): Group =
        transaction {
            GroupsTable.update({ GroupsTable.id eq group.id!!.value }) {
                it[name] = group.name
                it[description] = group.description
                it[parentGroupId] = group.parentGroupId?.value
                it[attributes] = serializeAttributes(group.attributes)
            }
            group
        }

    override fun delete(groupId: GroupId): Unit =
        transaction {
            GroupsTable.deleteWhere { id eq groupId.value }
        }

    // -------------------------------------------------------------------------
    // Group ↔ Role assignment
    // -------------------------------------------------------------------------

    override fun assignRoleToGroup(
        groupId: GroupId,
        roleId: RoleId,
    ): Unit =
        transaction {
            GroupRolesTable.insert {
                it[this.groupId] = groupId.value
                it[this.roleId] = roleId.value
                it[this.assignedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }

    override fun unassignRoleFromGroup(
        groupId: GroupId,
        roleId: RoleId,
    ): Unit =
        transaction {
            GroupRolesTable.deleteWhere {
                (GroupRolesTable.groupId eq groupId.value) and (GroupRolesTable.roleId eq roleId.value)
            }
        }

    override fun findRoleIdsForGroup(groupId: GroupId): List<RoleId> =
        transaction {
            GroupRolesTable
                .selectAll()
                .where { GroupRolesTable.groupId eq groupId.value }
                .map { RoleId(it[GroupRolesTable.roleId]) }
        }

    // -------------------------------------------------------------------------
    // User ↔ Group membership
    // -------------------------------------------------------------------------

    override fun addUserToGroup(
        userId: UserId,
        groupId: GroupId,
    ): Unit =
        transaction {
            UserGroupsTable.insert {
                it[this.userId] = userId.value
                it[this.groupId] = groupId.value
                it[this.joinedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }

    override fun removeUserFromGroup(
        userId: UserId,
        groupId: GroupId,
    ): Unit =
        transaction {
            UserGroupsTable.deleteWhere {
                (UserGroupsTable.userId eq userId.value) and (UserGroupsTable.groupId eq groupId.value)
            }
        }

    override fun findGroupsForUser(userId: UserId): List<Group> =
        transaction {
            (UserGroupsTable innerJoin GroupsTable)
                .selectAll()
                .where { UserGroupsTable.userId eq userId.value }
                .map { it.toGroup() }
        }

    override fun findUserIdsInGroup(groupId: GroupId): List<UserId> =
        transaction {
            UserGroupsTable
                .selectAll()
                .where { UserGroupsTable.groupId eq groupId.value }
                .map { UserId(it[UserGroupsTable.userId]) }
        }

    // -------------------------------------------------------------------------
    // Hierarchy traversal
    // -------------------------------------------------------------------------

    override fun findAncestorGroupIds(groupId: GroupId): List<GroupId> =
        transaction {
            val ancestors = mutableListOf<Int>()
            val visited = mutableSetOf(groupId.value)
            var currentId: Int? =
                GroupsTable
                    .selectAll()
                    .where { GroupsTable.id eq groupId.value }
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
            return@transaction ancestors.map { GroupId(it) }
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ResultRow.toGroup(): Group {
        val gid = this[GroupsTable.id]
        return Group(
            id = GroupId(gid),
            tenantId = TenantId(this[GroupsTable.tenantId]),
            name = this[GroupsTable.name],
            description = this[GroupsTable.description],
            parentGroupId = this[GroupsTable.parentGroupId]?.let { GroupId(it) },
            attributes = parseAttributes(this[GroupsTable.attributes]),
            roleIds =
                GroupRolesTable
                    .selectAll()
                    .where { GroupRolesTable.groupId eq gid }
                    .map { RoleId(it[GroupRolesTable.roleId]) },
            createdAt = this[GroupsTable.createdAt].toInstant(),
        )
    }

    private fun serializeAttributes(attrs: Map<String, String>): String {
        if (attrs.isEmpty()) return "{}"
        val obj = JsonObject(attrs.mapValues { (_, v) -> JsonPrimitive(v) })
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parseAttributes(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj.mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
