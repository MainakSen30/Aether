package dev.aether.domain

import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeMetadata
import kotlinx.coroutines.flow.Flow

interface ThemeRepository {
    fun observeActiveThemeId(): Flow<ThemeId?>
    fun observeMetadata(): Flow<List<ThemeMetadata>>
    suspend fun getMetadata(id: ThemeId): ThemeMetadata?
    suspend fun getDefinition(id: ThemeId): ThemeDefinition?
    suspend fun getDefinitions(): List<ThemeDefinition>
    suspend fun saveDefinition(theme: ThemeDefinition): Result<Unit>
    suspend fun saveDraft(theme: ThemeDefinition): Result<Unit>
    suspend fun getDraft(id: ThemeId): ThemeDefinition?
    suspend fun seedBuiltIns(definitions: List<ThemeDefinition>, defaultId: ThemeId): Result<Unit>
    suspend fun initialize(defaultId: ThemeId): Result<Unit>
    suspend fun activate(id: ThemeId): Result<Unit>
}
