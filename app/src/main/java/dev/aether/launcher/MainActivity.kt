package dev.aether.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.aether.designsystem.AetherTheme
import dev.aether.feature.launcher.LauncherScreen
import dev.aether.theme.ThemeController
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeController: ThemeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val resolvedTheme by themeController.active.collectAsState()
            LaunchedEffect(Unit) { themeController.load() }
            AetherTheme(resolvedTheme) {
                LauncherScreen()
            }
        }
    }
}
