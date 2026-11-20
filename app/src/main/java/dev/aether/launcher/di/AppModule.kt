package dev.aether.launcher.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.aether.assets.AppPrivateAssetStorage
import dev.aether.assets.AssetEngine
import dev.aether.assets.AssetPaths
import dev.aether.assets.AssetRecoveryInitializer
import dev.aether.assets.AssetStorage
import dev.aether.domain.AssetRepository
import dev.aether.domain.ThemeRepository
import dev.aether.theme.AetherThemeCatalog
import dev.aether.theme.ThemeCatalog
import dev.aether.theme.ThemeController
import dev.aether.theme.ThemeResolver
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAssetStorage(@ApplicationContext context: Context): AssetStorage =
        AppPrivateAssetStorage(File(context.filesDir, AssetPaths.ROOT_NAME))
            .also { it.initialize() }

    @Provides
    @Singleton
    fun provideAssetEngine(
        storage: AssetStorage,
        repository: AssetRepository,
    ): AssetEngine = AssetEngine(storage, repository)

    @Provides
    @Singleton
    fun provideAssetRecoveryInitializer(assetEngine: AssetEngine): AssetRecoveryInitializer =
        AssetRecoveryInitializer(
            assetEngine = assetEngine,
            dispatcher = Dispatchers.IO,
            onFailure = { failure ->
                Log.e("AssetRecovery", "Asset Engine startup recovery failed", failure)
            },
        )

    @Provides
    @Singleton
    fun provideThemeCatalog(): ThemeCatalog = AetherThemeCatalog

    @Provides
    @Singleton
    fun provideThemeResolver(): ThemeResolver =
        ThemeResolver(AetherThemeCatalog.AETHER_BLACK.tokens)

    @Provides
    @Singleton
    fun provideThemeController(
        repository: ThemeRepository,
        catalog: ThemeCatalog,
        resolver: ThemeResolver,
    ): ThemeController = ThemeController(repository, catalog, resolver)
}
