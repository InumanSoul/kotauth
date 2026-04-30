package com.kauth.infrastructure

import com.kauth.config.DbConfig
import org.flywaydb.core.Flyway

/**
 * Reads the highest applied Flyway migration version on the destination database.
 *
 * The backup format stamps an export with the schema V-number it was taken at;
 * the import side compares that stamp against this value to decide compatibility.
 * Querying Flyway's API (rather than `flyway_schema_history` directly) keeps us
 * decoupled from Flyway's internal table layout.
 */
object FlywaySchemaHead {
    fun read(config: DbConfig): Int {
        val flyway =
            Flyway
                .configure()
                .dataSource(config.dbUrl, config.dbUser, config.dbPassword)
                .locations("classpath:db/migration")
                .load()
        val version =
            flyway
                .info()
                .current()
                ?.version
                ?.version
                ?: error("No Flyway migrations have run on this database — cannot determine schema head")
        return version.toInt()
    }
}
