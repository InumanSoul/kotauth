package com.kauth.adapter.persistence

import com.kauth.domain.model.Group
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

    override fun findById(id: Int): Group? = transaction {
        GroupsTable.selectAll().where { GroupsTable.id eq id }
            .firstOrNull()?.toGroup()
    }

    override fun findByTenantId(tenantId: Int): List<Group> = transaction {
        GroupsTable.selectAll().where { GroupsTable.tenantId eq tenantId }
            .orderBy(GroupsTable.name)
            .map { it.toGroup() }
    }

    override fun findByName(tenantId: Int, name: String, parentGroupId: Int?): Group? = transaction {
        GroupsTable.selectAll().where {
            (GroupsTable.tenantId eq tenantId) and
            (GroupsTable.name eq name) and
            if (parentGroupId != null) (GroupsTable.parentGroupId eq parentGroupId)
            else (GroupsTable.parentGroupId.isNull())
        }.firstOrNull()?.toGroup()
    }

    override fun findChildren(groupId: Int): List<Group> = transaction {
        GroupsTable.selectAll().where { GroupsTable.parentGroupId eq groupId }
            .orderBy(GroupsTable.name)
            .map { it.toGroup() }
    }

    override fun save(group: Group): Group = transaction {
        val id = GroupsTable.insert {
            it[tenantId]      = group.tenantId
            it[name]          = group.name
            it[description]   = group.description
            it[parentGroupId] = group.parentGroupId
            it[attributes]    = serializeAttributes(group.attributes)
            it[createdAt]     = OffsetDateTime.now(ZoneOffset.UTC)
        } get GroupsTable.id

        group.copy(id = id)
    }

    override fun update(group: Group): Group = transaction {
        GroupsTable.update({ GroupsTable.id eq group.id!! }) {
            it[name]          = group.name
            it[description]   = group.description
            it[parentGroupId] = group.parentGroupId
            it[attributes]    = serializeAttributes(group.attributes)
        }
        group
    }

    override fun delete(groupId: Int): Unit = transaction {
        GroupsTable.deleteWhere { id eq groupId }
    }

    // -------------------------------------------------------------------------
    // Group ↔ Role assignment
    // -------------------------------------------------------------------------

    override fun assignRoleToGroup(groupId: Int, roleId: Int): Unit = transaction {
        GroupRolesTable.insert {
            it[this.groupId]    = groupId
            it[this.roleId]     = roleId
            it[this.assignedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override fun unassignRoleFromGroup(groupId: Int, roleId: Int): Unit = transaction {
        GroupRolesTable.deleteWhere {
            (GroupRolesTable.groupId eq groupId) and (GroupRolesTable.roleId eq roleId)
        }
    }

    override fun findRoleIdsForGroup(groupId: Int): List<Int> = transaction {
        GroupRolesTable.selectAll()
            .where { GroupRolesTable.groupId eq groupId }
            .map { it[GroupRolesTable.roleId] }
    }

    // -------------------------------------------------------------------------
    // User ↔ Group membership
    // -------------------------------------------------------------------------

    override fun addUserToGroup(userId: Int, groupId: Int): Unit = transaction {
        UserGroupsTable.insert {
            it[this.userId]  = userId
            it[this.groupId] = groupId
            it[this.joinedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    override fun removeUserFromGroup(userId: Int, groupId: Int): Unit = transaction {
        UserGroupsTable.deleteWhere {
            (UserGroupsTable.userId eq userId) and (UserGroupsTable.groupId eq groupId)
        }
    }

    override fun findGroupsForUser(userId: Int): List<Group> = transaction {
        (UserGroupsTable innerJoin GroupsTable)
            .selectAll().where { UserGroupsTable.userId eq userId }
            .map { it.toGroup() }
    }

    override fun findUserIdsInGroup(groupId: Int): List<Int> = transaction {
        UserGroupsTable.selectAll()
            .where { UserGroupsTable.groupId eq groupId }
            .map { it[UserGroupsTable.userId] }
    }

    // -------------------------------------------------------------------------
    // Hierarchy traversal
    // -------------------------------------------------------------------------

    override fun findAncestorGroupIds(groupId: Int): List<Int> = transaction {
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
        return@transaction ancestors
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ResultRow.toGroup(): Group {
        val gid = this[GroupsTable.id]
        return Group(
            id            = gid,
            tenantId      = this[GroupsTable.tenantId],
            name          = this[GroupsTable.name],
            description   = this[GroupsTable.description],
            parentGroupId = this[GroupsTable.parentGroupId],
            attributes    = parseAttributes(this[GroupsTable.attributes]),
            roleIds       = GroupRolesTable.selectAll()
                .where { GroupRolesTable.groupId eq gid }
                .map { it[GroupRolesTable.roleId] },
            createdAt     = this[GroupsTable.createdAt].toInstant()
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
