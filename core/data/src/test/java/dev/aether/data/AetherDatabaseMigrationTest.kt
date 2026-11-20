package dev.aether.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AetherDatabaseMigrationTest {
    @Test
    fun `migration 1 to 2 adds definition columns without destructive statements`() {
        val executed = mutableListOf<String>()
        val db = recordingDatabase(executed)

        MIGRATION_1_2.migrate(db)

        assertEquals(
            listOf(
                "ALTER TABLE theme_metadata ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE theme_metadata ADD COLUMN definitionJson TEXT",
                "ALTER TABLE theme_metadata ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0",
                "CREATE TABLE IF NOT EXISTS `theme_drafts` (`id` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `definitionJson` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            ),
            executed,
        )
    }
}

private fun recordingDatabase(executed: MutableList<String>): SupportSQLiteDatabase =
    Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        if (method.name == "execSQL" && args?.firstOrNull() is String) {
            executed += args.first() as String
        }
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    } as SupportSQLiteDatabase
