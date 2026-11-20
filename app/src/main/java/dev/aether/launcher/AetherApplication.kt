package dev.aether.launcher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.aether.assets.AssetRecoveryInitializer
import javax.inject.Inject

@HiltAndroidApp
class AetherApplication : Application() {
    @Inject
    lateinit var assetRecoveryInitializer: AssetRecoveryInitializer

    override fun onCreate() {
        super.onCreate()
        assetRecoveryInitializer.start()
    }
}
