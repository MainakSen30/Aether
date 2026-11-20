package dev.aether.data

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.aether.data.dao.AssetDao
import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.AssetImportRecordEntity
import dev.aether.data.entity.AssetMetadataEntity
import dev.aether.data.entity.AssetReferenceEntity
import dev.aether.data.entity.ThemeMetadataEntity

@Database(
    entities = [
        ThemeMetadataEntity::class,
        AssetMetadataEntity::class,
        AssetReferenceEntity::class,
        AssetImportRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun themeMetadataDao(): ThemeMetadataDao
    abstract fun assetDao(): AssetDao
}
