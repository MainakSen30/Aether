package dev.aether.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aether.theme.ThemeController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LauncherViewModel @Inject constructor(
    themeController: ThemeController,
) : ViewModel() {
    val uiState: StateFlow<LauncherUiState> = themeController.active
        .map { resolved ->
            LauncherUiState(
                foundationReady = resolved != null,
                activeThemeName = resolved?.id?.value ?: "Aether Black",
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LauncherUiState(),
        )
}
