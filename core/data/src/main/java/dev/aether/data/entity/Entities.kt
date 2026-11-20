package dev.aether.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "theme_metadata")
data class ThemeMetadataEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val origin: String,
    val schemaVersion: Int = 0,
    val definitionJson: String? = null,
    val updatedAtMillis: Long = 0,
)

@Entity(tableName = "theme_drafts")
data class ThemeDraftEntity(
    @PrimaryKey val id: String,
    val schemaVersion: Int,
    val definitionJson: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "asset_metadata")
data class AssetMetadataEntity(
    @PrimaryKey val id: String,
    val category: String,
    val displayName: String,
    val relativePath: String,
    val byteSize: Long,
    val checksumSha256: String,
    val state: String,
    val ownership: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "asset_references",
    primaryKeys = ["assetId", "consumerType", "consumerId"],
)
data class AssetReferenceEntity(
    val assetId: String,
    val consumerType: String,
    val consumerId: String,
)

@Entity(tableName = "asset_imports")
data class AssetImportRecordEntity(
    @PrimaryKey val importId: String,
    val sourceName: String,
    val status: String,
    val assetId: String?,
    val createdAtMillis: Long,
)
