package com.fitform.app.analysis

import android.content.Context
import android.util.Log
import com.fitform.app.model.ExerciseMode
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Second LiteRT model: a tiny Dense network trained on biomechanical angle
 * features to produce a calibrated form-quality score.
 *
 * Input:  float32[1, 4]  — normalized angle/geometry features from [AngleExtractor]
 * Output: float32[1, 1]  — quality score in [0, 1]
 *
 * Runs on the same NNAPI (Hexagon NPU) delegate as MoveNet, adding < 1 ms of
 * additional inference latency.
 *
 * Instantiate via [tryCreate]; returns null when the model asset is absent so
 * the app degrades gracefully to the rule-based score alone.
 */
class FormClassifier private constructor(
    private val interpreter: Interpreter,
) {

    fun classify(features: FloatArray): Float {
        require(features.size == INPUT_FEATURES)

        val inputBuf = ByteBuffer.allocateDirect(INPUT_FEATURES * FLOAT_BYTES).apply {
            order(ByteOrder.nativeOrder())
            features.forEach { putFloat(it) }
            rewind()
        }
        val outputBuf = ByteBuffer.allocateDirect(FLOAT_BYTES).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter.run(inputBuf, outputBuf)
        outputBuf.rewind()
        return outputBuf.float.coerceIn(0f, 1f)
    }

    fun close() = runCatching { interpreter.close() }

    companion object {
        private const val TAG = "FormClassifier"
        private const val INPUT_FEATURES = 4
        private const val FLOAT_BYTES = 4

        private fun assetName(mode: ExerciseMode) = when (mode) {
            ExerciseMode.GYM  -> "form_classifier_squat.tflite"
            ExerciseMode.SHOT -> "form_classifier_shot.tflite"
        }

        /**
         * Returns null if the model asset does not exist or fails to load.
         * The app continues with pure rule-based scoring in that case.
         */
        fun tryCreate(context: Context, mode: ExerciseMode): FormClassifier? = try {
            val name = assetName(mode)
            val bytes = context.assets.open(name).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
            val options = Interpreter.Options().apply {
                addDelegate(NnApiDelegate())
                numThreads = 2
            }
            val interp = Interpreter(buf, options)
            Log.i(TAG, "FormClassifier loaded: $name (${bytes.size / 1024} KB)")
            FormClassifier(interp)
        } catch (e: Throwable) {
            Log.d(TAG, "FormClassifier not available — rule-based scoring only (${e.message})")
            null
        }
    }
}
