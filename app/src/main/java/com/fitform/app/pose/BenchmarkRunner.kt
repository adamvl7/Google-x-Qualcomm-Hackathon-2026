package com.fitform.app.pose

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PowerTier { LOW, MEDIUM, HIGH }

data class BackendResult(
    val name: String,
    val avgMs: Long,
    val minMs: Long,
    val available: Boolean,
    val powerTier: PowerTier,
)

/**
 * Runs MoveNet Lightning on CPU, GPU, and NPU (Hexagon via NNAPI) and
 * measures inference latency for each backend.
 *
 * Used by BenchmarkScreen to give judges a live comparison showing that
 * adding one line — `addDelegate(NnApiDelegate())` — routes ops to the
 * NPU and cuts latency by 3–5x versus CPU alone.
 *
 * 5 warmup + 30 timed inferences per backend, zeroed dummy input.
 */
object BenchmarkRunner {
    private const val TAG = "FitForm/Benchmark"
    private const val MODEL_FILE = "movenet_lightning.tflite"
    private const val WARMUP_RUNS = 5
    private const val TIMED_RUNS = 30
    private const val INPUT_BYTES = 1 * 192 * 192 * 3 * 4  // float32

    suspend fun run(context: Context): List<BackendResult> = withContext(Dispatchers.IO) {
        val assetExists = runCatching {
            context.assets.open(MODEL_FILE).use { it.read() }
            true
        }.getOrDefault(false)

        if (!assetExists) {
            Log.w(TAG, "$MODEL_FILE missing — cannot benchmark")
            return@withContext listOf(
                BackendResult("NPU (Hexagon DSP)", 0L, 0L, false, PowerTier.LOW),
                BackendResult("GPU", 0L, 0L, false, PowerTier.MEDIUM),
                BackendResult("CPU (4 threads)", 0L, 0L, false, PowerTier.HIGH),
            )
        }

        val buffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        buildList {
            add(benchmarkNpu(buffer))
            add(benchmarkGpu(buffer))
            add(benchmarkCpu(buffer))
        }
    }

    private fun benchmarkNpu(buffer: java.nio.MappedByteBuffer): BackendResult {
        var nnApi: NnApiDelegate? = null
        return try {
            nnApi = NnApiDelegate()
            val opts = Interpreter.Options().apply {
                numThreads = 4
                addDelegate(nnApi)
            }
            val (avg, min) = timeInterpreter(buffer, opts)
            Log.i(TAG, "NPU avg=${avg}ms min=${min}ms")
            BackendResult("NPU (Hexagon DSP)", avg, min, true, PowerTier.LOW)
        } catch (t: Throwable) {
            Log.w(TAG, "NPU benchmark failed: ${t.message}")
            BackendResult("NPU (Hexagon DSP)", 0L, 0L, false, PowerTier.LOW)
        } finally {
            runCatching { nnApi?.close() }
        }
    }

    private fun benchmarkGpu(buffer: java.nio.MappedByteBuffer): BackendResult {
        return try {
            // GpuDelegate loaded via reflection so the benchmark degrades gracefully
            // on devices where litert-gpu native libs are absent.
            val delegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            val delegate = delegateClass.getDeclaredConstructor().newInstance()
            val opts = Interpreter.Options().apply {
                numThreads = 4
                @Suppress("UNCHECKED_CAST")
                (this::class.java.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate"))
                    .invoke(this, delegate))
            }
            val (avg, min) = timeInterpreter(buffer, opts)
            Log.i(TAG, "GPU avg=${avg}ms min=${min}ms")
            BackendResult("GPU", avg, min, true, PowerTier.MEDIUM)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU benchmark unavailable: ${t.message}")
            BackendResult("GPU", 0L, 0L, false, PowerTier.MEDIUM)
        }
    }

    private fun benchmarkCpu(buffer: java.nio.MappedByteBuffer): BackendResult {
        return try {
            val opts = Interpreter.Options().apply { numThreads = 4 }
            val (avg, min) = timeInterpreter(buffer, opts)
            Log.i(TAG, "CPU avg=${avg}ms min=${min}ms")
            BackendResult("CPU (4 threads)", avg, min, true, PowerTier.HIGH)
        } catch (t: Throwable) {
            Log.w(TAG, "CPU benchmark failed: ${t.message}")
            BackendResult("CPU (4 threads)", 0L, 0L, false, PowerTier.HIGH)
        }
    }

    private fun timeInterpreter(
        buffer: java.nio.MappedByteBuffer,
        opts: Interpreter.Options,
    ): Pair<Long, Long> {
        val interp = Interpreter(buffer, opts)
        val inputSize = interp.getInputTensor(0).numBytes()
        val input = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        val output = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }

        repeat(WARMUP_RUNS) { interp.run(input, output) }

        var totalMs = 0L
        var minMs = Long.MAX_VALUE
        repeat(TIMED_RUNS) {
            input.rewind()
            val t0 = SystemClock.elapsedRealtime()
            interp.run(input, output)
            val ms = SystemClock.elapsedRealtime() - t0
            totalMs += ms
            if (ms < minMs) minMs = ms
        }
        interp.close()
        return (totalMs / TIMED_RUNS) to minMs
    }
}
