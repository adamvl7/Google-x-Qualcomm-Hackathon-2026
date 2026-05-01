package com.fitform.app.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MoveNet Lightning runner backed by LiteRT (Google AI Edge, v1.0.1).
 *
 * Model contract (MoveNet/SinglePose/Lightning):
 *   input  : [1, 192, 192, 3] float32, RGB normalized 0..1
 *   output : [1, 1, 17, 3] float32 — each keypoint row is [y, x, score]
 *             all coordinates are normalized to [0, 1]
 *
 * NPU path:
 *   The NNAPI delegate is added to [Interpreter.Options] before the
 *   interpreter is constructed. On Snapdragon devices this routes
 *   supported ops through the Hexagon DSP, giving ~5–8 ms per frame
 *   vs ~25–40 ms on CPU-only. Inference latency is logged every
 *   LOG_INTERVAL_FRAMES frames so it can be monitored in Logcat.
 *
 * Fallback chain:
 *   NNAPI delegate init fails  → CPU multi-threaded (numThreads = 4)
 *   .tflite asset missing      → tryCreate() returns null
 *                                → caller uses MockPoseEstimator
 */
class LiteRtPoseEstimator private constructor(
    private val interpreter: Interpreter,
    private val nnApiDelegate: NnApiDelegate?,
    val usingNnApi: Boolean,
) : PoseEstimator {

    private val inputTensor = interpreter.getInputTensor(0)
    private val inputDataType: DataType = inputTensor.dataType()
    private val inputBufferSize: Int = inputTensor.numBytes()

    private val processor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputBufferSize)
        .order(ByteOrder.nativeOrder())

    private val outputBuffer = Array(1) { Array(1) { Array(KeypointIndex.COUNT) { FloatArray(3) } } }

    // Most recent inference latency — read by LiveCoachViewModel each frame for the HUD chip.
    @Volatile var lastInferenceMs: Long = 0L

    // Perf tracking — accumulated across frames, single-threaded via LiveCoachViewModel's Mutex.
    private var frameCount = 0
    private var rollingTotalMs = 0L

    override suspend fun estimatePose(frame: ImageProxy): PoseResult? = withContext(Dispatchers.Default) {
        val timestamp = frame.imageInfo.timestamp / 1_000_000L  // ns → ms
        try {
            val bitmap = frame.toUprightBitmap()
            val tensor = TensorImage.fromBitmap(bitmap)
            val resized = processor.process(tensor)

            inputBuffer.rewind()
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (px in pixels) {
                val r = ((px shr 16) and 0xFF)
                val g = ((px shr 8) and 0xFF)
                val b = (px and 0xFF)
                if (inputDataType == DataType.FLOAT32) {
                    inputBuffer.putFloat(r / 255f)
                    inputBuffer.putFloat(g / 255f)
                    inputBuffer.putFloat(b / 255f)
                } else {
                    inputBuffer.put(r.toByte())
                    inputBuffer.put(g.toByte())
                    inputBuffer.put(b.toByte())
                }
            }
            inputBuffer.rewind()

            val inferenceStart = SystemClock.elapsedRealtime()
            interpreter.run(inputBuffer, outputBuffer)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
            lastInferenceMs = inferenceMs

            frameCount++
            rollingTotalMs += inferenceMs
            if (frameCount % LOG_INTERVAL_FRAMES == 0) {
                val avg = rollingTotalMs / frameCount
                val backend = if (usingNnApi) "NPU/NNAPI" else "CPU"
                Log.i(TAG, "[$backend] frame=$frameCount  last=${inferenceMs}ms  avg=${avg}ms")
            }

            val raw = outputBuffer[0][0]
            val keypoints = ArrayList<Keypoint>(KeypointIndex.COUNT)
            for (i in 0 until KeypointIndex.COUNT) {
                // MoveNet output order within each row: [y, x, confidence]
                val y = raw[i][0]
                val x = raw[i][1]
                val score = raw[i][2]
                keypoints.add(Keypoint(x = x, y = y, confidence = score))
            }
            PoseResult(timestampMs = timestamp, keypoints = keypoints)
        } catch (t: Throwable) {
            Log.w(TAG, "estimatePose failed", t)
            null
        } finally {
            frame.close()
        }
    }

    /**
     * Runs one dummy inference to force the NPU to compile and cache the
     * execution plan for this model. Without warmup, the first live frame
     * incurs a cold-start spike (50–150ms on NNAPI) while the Hexagon
     * runtime allocates buffers and JIT-compiles the delegate. Subsequent
     * frames then settle to steady-state latency (~5–8ms on S25 Ultra).
     *
     * Called synchronously inside [tryCreate] before the estimator is
     * returned, so all live frames arrive after the NPU is primed.
     */
    fun warmup() {
        val dummyInput = ByteBuffer
            .allocateDirect(inputBufferSize)
            .order(ByteOrder.nativeOrder())
        val dummyOutput = Array(1) { Array(1) { Array(KeypointIndex.COUNT) { FloatArray(3) } } }
        val t0 = SystemClock.elapsedRealtime()
        runCatching { interpreter.run(dummyInput, dummyOutput) }
        val warmupMs = SystemClock.elapsedRealtime() - t0
        val backend = if (usingNnApi) "NPU/NNAPI" else "CPU"
        Log.i(TAG, "[$backend] warmup complete: ${warmupMs}ms (NPU execution plan cached — live frames will be faster)")
    }

    override fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { nnApiDelegate?.close() } catch (_: Throwable) {}
        Log.i(TAG, "LiteRtPoseEstimator closed — total frames=$frameCount  avg=${if (frameCount > 0) rollingTotalMs / frameCount else 0}ms")
    }

    companion object {
        private const val TAG = "FitForm/LiteRT"
        private const val INPUT_SIZE = 192
        private const val MODEL_FILE = "movenet_lightning.tflite"
        // Log perf every N frames (~1 second at 30 fps).
        private const val LOG_INTERVAL_FRAMES = 30

        /**
         * Loads MoveNet from assets and builds the interpreter.
         * Returns null if the model file is missing — the caller
         * should fall back to [MockPoseEstimator].
         *
         * Attempts NNAPI delegate first (Hexagon NPU on Snapdragon).
         * If that fails, retries with CPU-only options.
         */
        fun tryCreate(context: Context): LiteRtPoseEstimator? {
            val assetExists = runCatching {
                context.assets.open(MODEL_FILE).use { it.read() }
                true
            }.getOrDefault(false)
            if (!assetExists) {
                Log.w(TAG, "$MODEL_FILE not found in assets — caller should use MockPoseEstimator")
                return null
            }

            Log.i(TAG, "Loading $MODEL_FILE from assets (LiteRT 1.0.1)")
            val buffer = FileUtil.loadMappedFile(context, MODEL_FILE)

            // Attempt NNAPI delegate → Hexagon NPU on Snapdragon 8 Elite.
            var nnApi: NnApiDelegate? = null
            val options = Interpreter.Options().apply { numThreads = 4 }
            var usingNnApi = false
            try {
                nnApi = NnApiDelegate()
                options.addDelegate(nnApi)
                usingNnApi = true
                Log.i(TAG, "NNAPI delegate created — inference will route to Hexagon NPU")
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI delegate unavailable — falling back to CPU (numThreads=4)", t)
                runCatching { nnApi?.close() }
                nnApi = null
            }

            val interpreter = try {
                try {
                    Interpreter(buffer, options).also {
                        val input = it.getInputTensor(0)
                        Log.i(
                            TAG,
                            "Interpreter ready | backend=${if (usingNnApi) "NNAPI/NPU" else "CPU"} | input=${INPUT_SIZE}x${INPUT_SIZE}x3 | dtype=${input.dataType()} | bytes=${input.numBytes()}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Interpreter init with NNAPI failed — retrying CPU-only", t)
                    runCatching { nnApi?.close() }
                    nnApi = null
                    usingNnApi = false
                    Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 }).also {
                        Log.i(TAG, "Interpreter ready | backend=CPU (NNAPI fallback)")
                    }
                }
            } catch (t: Throwable) {
                // Model bytes are invalid (corrupt file, wrong format, incomplete download).
                Log.e(TAG, "$MODEL_FILE is not a valid TFLite flatbuffer — falling back to MockPoseEstimator. Re-download from TFHub.", t)
                runCatching { nnApi?.close() }
                return null
            }
            val estimator = LiteRtPoseEstimator(interpreter, nnApi, usingNnApi)
            estimator.warmup()
            return estimator
        }
    }
}

/**
 * Convert an [ImageProxy] into an upright RGB [Bitmap].
 *
 * [ImageProxy.toBitmap] works when the analyzer requests RGBA_8888.
 * Falls back to YUV → NV21 → JPEG → Bitmap for other formats.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val src: Bitmap = try {
        toBitmap()
    } catch (_: Throwable) {
        yuvToBitmap(this)
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return src
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private fun yuvToBitmap(image: ImageProxy): Bitmap {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuv = android.graphics.YuvImage(
        nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null,
    )
    val out = java.io.ByteArrayOutputStream()
    yuv.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
    val bytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
