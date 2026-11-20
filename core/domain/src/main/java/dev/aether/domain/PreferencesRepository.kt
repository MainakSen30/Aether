package dev.aether.domain

import dev.aether.model.AetherSettings
import kotlinx.coroutines.flow.Flow

interface PreferencesRepository {
    val settings: Flow<AetherSettings>
    suspend fun update(transform: (AetherSettings) -> AetherSettings)
}
