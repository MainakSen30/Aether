package dev.aether.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.aether.data.AetherDatabase
import dev.aether.data.dao.AssetDao
import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.preferences.AetherPreferences
import dev.aether.data.preferences.aetherDataStore
import dev.aether.data.repository.AssetRepositoryImpl
import dev.aether.data.repository.ThemeRepositoryImpl
import dev.aether.domain.AssetRepository
import dev.aether.domain.PreferencesRepository
import dev.aether.domain.ThemeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AetherDatabase =
        Room.databaseBuilder(context, AetherDatabase::class.java, "aether.db").build()

    @Provides
    fun provideThemeMetadataDao(database: AetherDatabase): ThemeMetadataDao = database.themeMetadataDao()

    @Provides
    fun provideAssetDao(database: AetherDatabase): AssetDao = database.assetDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.aetherDataStore

    @Provides
    fun providePreferencesRepository(dataStore: DataStore<Preferences>): PreferencesRepository =
        AetherPreferences(dataStore)

    @Provides
    fun provideThemeRepository(
        dao: ThemeMetadataDao,
        preferences: PreferencesRepository,
    ): ThemeRepository = ThemeRepositoryImpl(dao, preferences)

    @Provides
    fun provideAssetRepository(dao: AssetDao): AssetRepository = AssetRepositoryImpl(dao)
}
