package dev.aether.launcher.di

import dagger.Provides
import dev.aether.assets.AssetEngine
import dev.aether.assets.AssetStorage
import dev.aether.domain.AssetRepository
import org.junit.Assert.assertNotNull
import org.junit.Test
import javax.inject.Inject
import javax.inject.Singleton

class AppModuleTest {
    @Test
    fun `startup uses the same singleton asset engine provider`() {
        val engineProvider = AppModule::class.java.getDeclaredMethod(
            "provideAssetEngine",
            AssetStorage::class.java,
            AssetRepository::class.java,
        )
        val startupProvider = AppModule::class.java.getDeclaredMethod(
            "provideAssetRecoveryInitializer",
            AssetEngine::class.java,
        )

        assertNotNull(engineProvider.getAnnotation(Provides::class.java))
        assertNotNull(engineProvider.getAnnotation(Singleton::class.java))
        assertNotNull(startupProvider.getAnnotation(Provides::class.java))
        assertNotNull(startupProvider.getAnnotation(Singleton::class.java))
    }

    @Test
    fun `application injects the startup recovery initializer`() {
        val field = dev.aether.launcher.AetherApplication::class.java
            .getDeclaredField("assetRecoveryInitializer")

        assertNotNull(field.getAnnotation(Inject::class.java))
    }
}
