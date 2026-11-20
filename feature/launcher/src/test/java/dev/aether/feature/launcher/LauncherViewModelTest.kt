package dev.aether.feature.launcher

import dev.aether.domain.ThemeRepository
import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeMetadata
import dev.aether.theme.AetherThemeCatalog
import dev.aether.theme.ThemeController
import dev.aether.theme.ThemeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state reflects loaded theme without mutating it`() = runTest(dispatcher) {
        val repository = FakeThemeRepository()
        val controller = ThemeController(
            repository,
            AetherThemeCatalog,
            ThemeResolver(AetherThemeCatalog.AETHER_BLACK.tokens),
        )
        val viewModel = LauncherViewModel(controller)
        assertFalse(viewModel.uiState.value.foundationReady)

        controller.initialize()
        assertTrue(viewModel.uiState.value.foundationReady)
        assertEquals("aether.black", viewModel.uiState.value.activeThemeName)

        repository.saveDraft(AetherThemeCatalog.AETHER_BLACK.copy(id = ThemeId("draft.one")))
        assertEquals("aether.black", viewModel.uiState.value.activeThemeName)
    }
}

private class FakeThemeRepository : ThemeRepository {
    private val active = MutableStateFlow<ThemeId?>(null)
    private val definitions = mutableMapOf<ThemeId, ThemeDefinition>()
    override fun observeActiveThemeId(): Flow<ThemeId?> = active
    override fun observeMetadata(): Flow<List<ThemeMetadata>> = flowOf(emptyList())
    override suspend fun getMetadata(id: ThemeId): ThemeMetadata? = null
    override suspend fun getDefinition(id: ThemeId): ThemeDefinition? = definitions[id]
    override suspend fun getDefinitions(): List<ThemeDefinition> = definitions.values.toList()
    override suspend fun saveDefinition(theme: ThemeDefinition) {
        definitions[theme.id] = theme
    }
    override suspend fun saveDraft(theme: ThemeDefinition) = Unit
    override suspend fun getDraft(id: ThemeId): ThemeDefinition? = null
    override suspend fun seedBuiltIns(
        definitions: List<ThemeDefinition>,
        defaultId: ThemeId,
    ): Result<Unit> = Result.success(Unit)
    override suspend fun initialize(defaultId: ThemeId): Result<Unit> {
        saveDefinition(AetherThemeCatalog.AETHER_BLACK)
        if (active.value == null) active.value = defaultId
        return Result.success(Unit)
    }
    override suspend fun activate(id: ThemeId): Result<Unit> {
        active.value = id
        definitions[id]?.let { saveDefinition(it) }
        return Result.success(Unit)
    }
}
