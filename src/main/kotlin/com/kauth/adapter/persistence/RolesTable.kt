package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * Exposed ORM mapping for the 'roles' table (V12 migration).
 */
object RolesTable : Table("roles") {
    val id          = integer("id").autoIncrement()
    val tenantId    = integer("tenant_id") references TenantsTable.id
    val name        = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val scope       = varchar("scope", 10).default("tenant")
    val clientId    = (integer("client_id") references ClientsTable.id).nullable()
    val createdAt   = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed ORM mapping for 'composite_role_mappings' (V12).
 */
object CompositeRoleMappingsTable : Table("composite_role_mappings") {
    val parentRoleId = integer("parent_role_id") references RolesTable.id
    val childRoleId  = integer("child_role_id") references RolesTable.id

    override val primaryKey = PrimaryKey(parentRoleId, childRoleId)
}

/**
 * Exposed ORM mapping for 'user_roles' (V12).
 */
object UserRolesTable : Table("user_roles") {
    val userId     = integer("user_id") references UsersTable.id
    val roleId     = integer("role_id") references RolesTable.id
    val assignedAt = timestampWithTimeZone("assigned_at")

    override val primaryKey = PrimaryKey(userId, roleId)
}
