package dev.aether.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.aether.data.entity.AssetImportRecordEntity
import dev.aether.data.entity.AssetMetadataEntity
import dev.aether.data.entity.AssetReferenceEntity
import dev.aether.data.entity.ThemeDraftEntity
import dev.aether.data.entity.ThemeMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemeMetadataDao {
    @Query("SELECT * FROM theme_metadata")
    fun observeAll(): Flow<List<ThemeMetadataEntity>>

    @Query("SELECT * FROM theme_metadata")
    suspend fun getAll(): List<ThemeMetadataEntity>

    @Query("SELECT * FROM theme_metadata WHERE id = :id")
    suspend fun get(id: String): ThemeMetadataEntity?

    @Query("SELECT * FROM theme_drafts")
    suspend fun getDrafts(): List<ThemeDraftEntity>

    @Query("SELECT * FROM theme_drafts WHERE id = :id")
    suspend fun getDraft(id: String): ThemeDraftEntity?

    @Query("SELECT COUNT(*) FROM theme_metadata WHERE id = :id")
    suspend fun count(id: String): Int

    @Insert
    suspend fun insert(entity: ThemeMetadataEntity)

    @Upsert
    suspend fun upsert(entity: ThemeMetadataEntity)

    @Upsert
    suspend fun upsertDraft(entity: ThemeDraftEntity)

    /** One Room transaction: any failure leaves no partially seeded definitions. */
    @Transaction
    suspend fun seedBuiltIns(definitions: List<ThemeMetadataEntity>, builtInOrigin: String) {
        definitions.forEach { entity ->
            when {
                count(entity.id) == 0 -> insert(entity)
                get(entity.id)?.origin == builtInOrigin -> upsert(entity)
            }
        }
    }
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM asset_metadata WHERE state = 'REGISTERED'")
    fun observeAssets(): Flow<List<AssetMetadataEntity>>

    @Query("SELECT * FROM asset_metadata")
    suspend fun getAll(): List<AssetMetadataEntity>

    @Query("SELECT * FROM asset_metadata WHERE id = :id")
    suspend fun get(id: String): AssetMetadataEntity?

    @Upsert
    suspend fun upsert(entity: AssetMetadataEntity)

    @Transaction
    suspend fun upsertWithImport(
        entity: AssetMetadataEntity,
        record: AssetImportRecordEntity,
    ) {
        upsert(entity)
        upsertImport(record)
    }

    @Transaction
    suspend fun updateWithImportStatus(
        entity: AssetMetadataEntity,
        status: String,
    ) {
        upsert(entity)
        setImportStatus(entity.id, status)
    }

    @Query("UPDATE asset_imports SET status = :status WHERE assetId = :assetId")
    suspend fun setImportStatus(assetId: String, status: String)

    @Query("DELETE FROM asset_metadata WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM asset_references WHERE assetId = :assetId")
    suspend fun referenceCount(assetId: String): Int

    @Upsert
    suspend fun addReference(reference: AssetReferenceEntity)

    @Query(
        "DELETE FROM asset_references " +
            "WHERE assetId = :assetId AND consumerType = :consumerType AND consumerId = :consumerId",
    )
    suspend fun removeReference(assetId: String, consumerType: String, consumerId: String)

    @Upsert
    suspend fun upsertImport(record: AssetImportRecordEntity)
}
