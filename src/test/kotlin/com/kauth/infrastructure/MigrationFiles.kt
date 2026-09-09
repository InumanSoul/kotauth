package com.kauth.infrastructure

import java.io.File

/** Reads the Flyway migration files off disk, so tests can assert on the chain itself. */
object MigrationFiles {
    private val NAME = Regex("""^V(\d+)__([a-z0-9_]+)\.sql$""")

    private fun directory(): File {
        // Walk up from the working directory so this works from the module root or the repo root.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/resources/db/migration")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate src/main/resources/db/migration")
    }

    fun files(): List<File> =
        directory().listFiles()?.filter { it.extension == "sql" }?.sortedBy { it.name } ?: emptyList()

    fun versionsOnDisk(): List<Int> =
        files().mapNotNull {
            NAME
                .find(it.name)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        }

    /** Names that do not match `V<number>__<lower_snake_case>.sql`. */
    fun malformedNames(): List<String> = files().map { it.name }.filterNot { NAME.matches(it) }

    /** Version numbers claimed by more than one file. */
    fun duplicateVersions(): Map<Int, List<String>> =
        files()
            .mapNotNull { f ->
                NAME
                    .find(f.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?.let { it to f.name }
            }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
}
