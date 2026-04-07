package com.kauth.adapter.persistence

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager

/**
 * Exposed column type for PostgreSQL `text[]` array columns.
 *
 * Exposed has no built-in support for PostgreSQL arrays. This custom type
 * handles JDBC ↔ Kotlin `List<String>` conversion using `java.sql.Array`.
 *
 * Usage:
 *   val tags = textArray("tags").default(emptyList())
 */
class TextArrayColumnType : ColumnType<List<String>>() {
    override fun sqlType(): String = "text[]"

    override fun valueFromDB(value: Any): List<String> =
        when (value) {
            is java.sql.Array -> (value.array as Array<*>).map { it.toString() }
            is Array<*> -> value.map { it.toString() }
            else -> emptyList()
        }

    override fun notNullValueToDB(value: List<String>): Any {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        return conn.createArrayOf("text", value.toTypedArray())
    }
}

fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, TextArrayColumnType())
