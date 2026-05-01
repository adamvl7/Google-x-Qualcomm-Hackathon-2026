package com.fitform.app.pose

import android.content.Context
import android.util.Log
import com.fitform.app.util.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class PerformanceState(
    val results: List<BackendResult>? = null,
    val isRunning: Boolean = false,
    val statusText: String? = null,
    val isEmulator: Boolean = false,
)

/**
 * Single source of truth for benchmark results. Both BenchmarkScreen and any
 * future surface (e.g. localhost dashboard) read from the same StateFlow so
 * they always agree on the numbers shown to the user.
 */
object PerformanceRepository {
    private const val TAG = "PerformanceLab"

    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        PerformanceState(isEmulator = DeviceInfo.isProbablyEmulator()),
    )
    val state: StateFlow<PerformanceState> = _state.asStateFlow()

    suspend fun runBenchmark(context: Context) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val isEmulator = DeviceInfo.isProbablyEmulator()
            Log.i(TAG, "Benchmark started (isEmulator=$isEmulator)")
            _state.value = _state.value.copy(
                isRunning = true,
                statusText = "Warming up…",
                isEmulator = isEmulator,
            )

            val results = BenchmarkRunner.run(context, isEmulator = isEmulator) { phase ->
                Log.i(TAG, phase.logMessage)
                _state.value = _state.value.copy(statusText = phase.statusText)
            }

            Log.i(TAG, "Benchmark complete")
            _state.value = _state.value.copy(
                results = results,
                isRunning = false,
                statusText = "Benchmark complete",
            )
        } finally {
            mutex.unlock()
        }
    }
}
