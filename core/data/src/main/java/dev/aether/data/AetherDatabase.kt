package dev.aether.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.aether.data.dao.AssetDao
import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.AssetImportRecordEntity
import dev.aether.data.entity.AssetMetadataEntity
import dev.aether.data.entity.AssetReferenceEntity
import dev.aether.data.entity.ThemeDraftEntity
import dev.aether.data.entity.ThemeMetadataEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE theme_metadata ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE theme_metadata ADD COLUMN definitionJson TEXT")
        db.execSQL("ALTER TABLE theme_metadata ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `theme_drafts` (" +
                "`id` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, " +
                "`definitionJson` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

@Database(
    entities = [
        ThemeMetadataEntity::class,
        ThemeDraftEntity::class,
        AssetMetadataEntity::class,
        AssetReferenceEntity::class,
        AssetImportRecordEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun themeMetadataDao(): ThemeMetadataDao
    abstract fun assetDao(): AssetDao
}
