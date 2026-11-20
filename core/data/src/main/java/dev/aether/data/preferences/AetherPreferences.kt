package dev.aether.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aether.domain.PreferencesRepository
import dev.aether.model.AetherSettings
import dev.aether.model.AppearanceMode
import dev.aether.model.ThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.aetherDataStore: DataStore<Preferences> by preferencesDataStore(name = "aether_settings")

private val ACTIVE_THEME_ID = stringPreferencesKey("active_theme_id")
private val GRID_COLUMNS = intPreferencesKey("grid_columns")
private val GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
private val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
private val APPEARANCE = stringPreferencesKey("appearance")

class AetherPreferences(private val dataStore: DataStore<Preferences>) : PreferencesRepository {
    override val settings: Flow<AetherSettings> = dataStore.data.map { read(it) }

    override suspend fun update(transform: (AetherSettings) -> AetherSettings) {
        dataStore.edit { preferences ->
            val next = transform(read(preferences))
            val themeId = next.activeThemeId
            if (themeId == null) {
                preferences.remove(ACTIVE_THEME_ID)
            } else {
                preferences[ACTIVE_THEME_ID] = themeId.value
            }
            preferences[GRID_COLUMNS] = next.gridColumns
            preferences[GESTURES_ENABLED] = next.gesturesEnabled
            preferences[ANIMATIONS_ENABLED] = next.animationsEnabled
            preferences[APPEARANCE] = next.appearance.name
        }
    }

    private fun read(preferences: Preferences): AetherSettings = AetherSettings(
        activeThemeId = preferences[ACTIVE_THEME_ID]?.let { runCatching { ThemeId(it) }.getOrNull() },
        gridColumns = preferences[GRID_COLUMNS] ?: AetherSettings().gridColumns,
        gesturesEnabled = preferences[GESTURES_ENABLED] ?: AetherSettings().gesturesEnabled,
        animationsEnabled = preferences[ANIMATIONS_ENABLED] ?: AetherSettings().animationsEnabled,
        appearance = runCatching {
            preferences[APPEARANCE]?.let { AppearanceMode.valueOf(it) }
        }.getOrNull() ?: AetherSettings().appearance,
    )
}
