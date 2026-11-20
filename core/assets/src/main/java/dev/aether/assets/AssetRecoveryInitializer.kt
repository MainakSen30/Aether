package dev.aether.assets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped, one-shot bridge from process startup to [AssetEngine.recover].
 * The engine is supplied by Hilt; this class only schedules and observes it.
 */
class AssetRecoveryInitializer(
    private val assetEngine: AssetEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val _completion = MutableStateFlow<Result<Unit>?>(null)

    /** Latest startup result; null until recovery finishes. */
    val completion: StateFlow<Result<Unit>?> = _completion.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val result = runCatching { assetEngine.recover().getOrThrow() }
            result.onFailure(onFailure)
            _completion.value = result
        }
    }
}
