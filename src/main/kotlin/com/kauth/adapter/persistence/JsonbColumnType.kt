package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

/**
 * Exposed column type for PostgreSQL JSONB columns.
 *
 * Exposed 0.50.x has no built-in JSONB support. Without this, any text value
 * inserted into a JSONB column is presented to the JDBC driver as
 * `character varying`, which Postgres refuses because there is no implicit
 * cast from varchar → jsonb.
 *
 * The fix: wrap the string in a [PGobject] with type = "jsonb" before handing
 * it to JDBC. That tells the Postgres driver the exact target type and the
 * insert succeeds.
 *
 * In Exposed 0.50.x, ColumnType is generic: ColumnType<T>. The type parameter
 * drives the method signatures — valueFromDB must return T, and
 * notNullValueToDB receives T as its parameter.
 *
 * Usage:
 *   val attributes = jsonb("attributes").default("{}")
 */
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        else        -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }
}

fun Table.jsonb(name: String): Column<String> =
    registerColumn(name, JsonbColumnType())
