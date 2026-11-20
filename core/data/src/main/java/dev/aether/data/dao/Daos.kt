package dev.aether.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.aether.data.entity.AssetImportRecordEntity
import dev.aether.data.entity.AssetMetadataEntity
import dev.aether.data.entity.AssetReferenceEntity
import dev.aether.data.entity.ThemeMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemeMetadataDao {
    @Query("SELECT * FROM theme_metadata")
    fun observeAll(): Flow<List<ThemeMetadataEntity>>

    @Query("SELECT * FROM theme_metadata WHERE id = :id")
    suspend fun get(id: String): ThemeMetadataEntity?

    @Upsert
    suspend fun upsert(entity: ThemeMetadataEntity)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM asset_metadata WHERE state != 'TRASHED'")
    fun observeAssets(): Flow<List<AssetMetadataEntity>>

    @Query("SELECT * FROM asset_metadata WHERE id = :id")
    suspend fun get(id: String): AssetMetadataEntity?

    @Upsert
    suspend fun upsert(entity: AssetMetadataEntity)

    @Query("UPDATE asset_metadata SET state = 'TRASHED' WHERE id = :id")
    suspend fun markTrashed(id: String)

    @Query("DELETE FROM asset_metadata WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM asset_references WHERE assetId = :assetId")
    suspend fun referenceCount(assetId: String): Int

    @Upsert
    suspend fun addReference(reference: AssetReferenceEntity)

    @Query("DELETE FROM asset_references WHERE assetId = :assetId AND consumerId = :consumerId")
    suspend fun removeReference(assetId: String, consumerId: String)

    @Upsert
    suspend fun recordImport(record: AssetImportRecordEntity)
}
