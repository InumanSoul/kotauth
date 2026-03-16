package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'groups' table (V12 migration).
 */
object GroupsTable : Table("groups") {
    val id            = integer("id").autoIncrement()
    val tenantId      = integer("tenant_id") references TenantsTable.id
    val name          = varchar("name", 100)
    val description   = varchar("description", 500).nullable()
    val parentGroupId = (integer("parent_group_id") references GroupsTable.id).nullable()
    val attributes    = jsonb("attributes").default("{}")   // JSONB — see JsonbColumnType
    val createdAt     = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed ORM mapping for 'group_roles' (V12).
 */
object GroupRolesTable : Table("group_roles") {
    val groupId    = integer("group_id") references GroupsTable.id
    val roleId     = integer("role_id") references RolesTable.id
    val assignedAt = timestampWithTimeZone("assigned_at")

    override val primaryKey = PrimaryKey(groupId, roleId)
}

/**
 * Exposed ORM mapping for 'user_groups' (V12).
 */
object UserGroupsTable : Table("user_groups") {
    val userId   = integer("user_id") references UsersTable.id
    val groupId  = integer("group_id") references GroupsTable.id
    val joinedAt = timestampWithTimeZone("joined_at")

    override val primaryKey = PrimaryKey(userId, groupId)
}
