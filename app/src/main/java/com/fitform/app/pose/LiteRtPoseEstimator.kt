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
import kotlin.math.sqrt

/**
 * MoveNet Lightning pose estimator backed by LiteRT + NNAPI delegate.
 *
 * Expected model contract:
 *   input  : [1, 192, 192, 3] float32 NHWC, RGB normalized 0–1
 *   output : [1, 1, 17, 3] — each keypoint is [y, x, confidence] in [0,1]
 *
 * The rest of the app uses a fixed 17-joint COCO contract; no remap needed
 * since MoveNet already outputs in COCO keypoint order.
 */
class LiteRtPoseEstimator private constructor(
    private val interpreter: Interpreter,
    private val nnApiDelegate: NnApiDelegate?,
    val usingNnApi: Boolean,
) : PoseEstimator {

    private val inputTensor = interpreter.getInputTensor(0)
    private val inputShape = inputTensor.shape()
    private val inputDataType: DataType = inputTensor.dataType()
    private val channelsFirst: Boolean = inputShape.getOrNull(1) == 3
    private val inputHeight: Int = if (channelsFirst) inputShape.getOrNull(2) ?: DEFAULT_INPUT_HEIGHT else inputShape.getOrNull(1) ?: DEFAULT_INPUT_HEIGHT
    private val inputWidth: Int = if (channelsFirst) inputShape.getOrNull(3) ?: DEFAULT_INPUT_WIDTH else inputShape.getOrNull(2) ?: DEFAULT_INPUT_WIDTH
    private val inputBufferSize: Int = inputTensor.numBytes()

    private val outputTensor = interpreter.getOutputTensor(0)
    private val outputShape = outputTensor.shape()

    private val processor = ImageProcessor.Builder()
        .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputBufferSize)
        .order(ByteOrder.nativeOrder())

    @Volatile var lastInferenceMs: Long = 0L

    private var frameCount = 0
    private var rollingTotalMs = 0L

    override suspend fun estimatePose(frame: ImageProxy): PoseResult? = withContext(Dispatchers.Default) {
        val timestamp = frame.imageInfo.timestamp / 1_000_000L
        try {
            val bitmap = frame.toUprightBitmap()
            val tensor = TensorImage.fromBitmap(bitmap)
            val resized = processor.process(tensor)

            inputBuffer.rewind()
            val pixels = IntArray(inputWidth * inputHeight)
            resized.bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (channelsFirst) writeNchwInput(pixels) else writeNhwcInput(pixels)
            inputBuffer.rewind()

            val outputBuffer = createOutputTemplate(outputShape)
            val inferenceStart = SystemClock.elapsedRealtime()
            interpreter.run(inputBuffer, outputBuffer)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
            lastInferenceMs = inferenceMs

            frameCount++
            rollingTotalMs += inferenceMs
            if (frameCount % LOG_INTERVAL_FRAMES == 0) {
                val avg = rollingTotalMs / frameCount
                val backend = if (usingNnApi) "NPU/NNAPI" else "CPU"
                Log.i(TAG, "[$backend] MoveNet frame=$frameCount last=${inferenceMs}ms avg=${avg}ms")
            }

            val keypoints = parseKeypoints(outputBuffer) ?: return@withContext null
            PoseResult(timestampMs = timestamp, keypoints = keypoints)
        } catch (t: Throwable) {
            Log.w(TAG, "estimatePose failed", t)
            null
        } finally {
            frame.close()
        }
    }

    fun warmup() {
        val dummyInput = ByteBuffer.allocateDirect(inputBufferSize).order(ByteOrder.nativeOrder())
        val dummyOutput = createOutputTemplate(outputShape)
        val t0 = SystemClock.elapsedRealtime()
        runCatching { interpreter.run(dummyInput, dummyOutput) }
        val warmupMs = SystemClock.elapsedRealtime() - t0
        val backend = if (usingNnApi) "NPU/NNAPI" else "CPU"
        Log.i(TAG, "[$backend] MoveNet warmup complete: ${warmupMs}ms")
    }

    override fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { nnApiDelegate?.close() } catch (_: Throwable) {}
        Log.i(TAG, "LiteRtPoseEstimator closed - total frames=$frameCount avg=${if (frameCount > 0) rollingTotalMs / frameCount else 0}ms")
    }

    private fun parseKeypoints(output: Any): List<Keypoint>? {
        parseHeatmaps(output)?.let { return it }

        val rows: Array<FloatArray> = when (output) {
            is Array<*> -> {
                when {
                    output.isNotEmpty() && output[0] is Array<*> && (output[0] as Array<*>).isNotEmpty() && (output[0] as Array<*>)[0] is Array<*> ->
                        ((output[0] as Array<*>)[0] as? Array<FloatArray>)
                    output.isNotEmpty() && output[0] is Array<*> ->
                        output[0] as? Array<FloatArray>
                    else -> null
                }
            }
            else -> null
        } ?: return null

        if (rows.size < KeypointIndex.COUNT) {
            Log.w(TAG, "MoveNet output has only ${rows.size} joints; expected ${KeypointIndex.COUNT}")
            return null
        }

        val xScale = inferCoordinateScale(
            values = rows.take(KeypointIndex.COUNT).mapNotNull { it.getOrNull(X_INDEX) },
            inputSize = inputWidth,
            heatmapSize = MOVENET_HEATMAP_WIDTH,
        )
        val yScale = inferCoordinateScale(
            values = rows.take(KeypointIndex.COUNT).mapNotNull { it.getOrNull(Y_INDEX) },
            inputSize = inputHeight,
            heatmapSize = MOVENET_HEATMAP_HEIGHT,
        )

        if (frameCount <= RAW_OUTPUT_LOG_FRAMES) {
            val rawX = rows.take(KeypointIndex.COUNT).mapNotNull { it.getOrNull(X_INDEX) }
            val rawY = rows.take(KeypointIndex.COUNT).mapNotNull { it.getOrNull(Y_INDEX) }
            Log.i(
                TAG,
                "MoveNet raw coords x=${rawX.minOrNull()}..${rawX.maxOrNull()} y=${rawY.minOrNull()}..${rawY.maxOrNull()} scale=${xScale}x${yScale}",
            )
        }

        val points = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        for (modelIndex in 0 until KeypointIndex.COUNT) {
            val row = rows[modelIndex]
            val appIndex = MODEL_TO_APP_INDEX[modelIndex]
            val x = normalizeCoordinate(row.getOrNull(X_INDEX) ?: 0f, xScale)
            val y = normalizeCoordinate(row.getOrNull(Y_INDEX) ?: 0f, yScale)
            val confidence = row.getOrNull(CONFIDENCE_INDEX)?.coerceIn(0f, 1f) ?: DEFAULT_KEYPOINT_CONFIDENCE
            points[appIndex] = Keypoint(x = x, y = y, confidence = confidence)
        }
        // PoseOverlay already does its own bone-plausibility filtering; no need to
        // suppress here. MoveNet direct-coord output has no per-joint confidence,
        // so the bone-length suppression was zeroing out the upper body on full-body frames
        // where shoulder→hip can exceed the old 0.38 threshold.
        return points
    }

    private fun writeNhwcInput(pixels: IntArray) {
        for (px in pixels) {
            putChannel((px shr 16) and 0xFF)
            putChannel((px shr 8) and 0xFF)
            putChannel(px and 0xFF)
        }
    }

    private fun writeNchwInput(pixels: IntArray) {
        for (channel in 0 until 3) {
            for (px in pixels) {
                val value = when (channel) {
                    0 -> (px shr 16) and 0xFF
                    1 -> (px shr 8) and 0xFF
                    else -> px and 0xFF
                }
                putChannel(value)
            }
        }
    }

    private fun putChannel(value: Int) {
        if (inputDataType == DataType.FLOAT32) {
            inputBuffer.putFloat(value / 255f)
        } else {
            inputBuffer.put(value.toByte())
        }
    }

    private fun normalizeCoordinate(value: Float, scale: Float): Float = when {
        value.isNaN() -> 0f
        value in 0f..1f -> value
        else -> (value / scale).coerceIn(0f, 1f)
    }

    private fun inferCoordinateScale(values: List<Float>, inputSize: Int, heatmapSize: Int): Float {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return inputSize.toFloat()
        val max = finite.maxOrNull() ?: return inputSize.toFloat()
        return when {
            max <= 1.5f -> 1f
            // Qualcomm MoveNet direct keypoint exports commonly report argmax
            // coordinates in the final heatmap grid, not full input pixels.
            max <= heatmapSize + HEATMAP_TOLERANCE -> heatmapSize.toFloat()
            else -> inputSize.toFloat()
        }
    }

    private fun suppressImplausibleKeypoints(points: List<Keypoint>): List<Keypoint> {
        val connected = BooleanArray(KeypointIndex.COUNT)
        for ((aIdx, bIdx) in KeypointIndex.SKELETON_EDGES) {
            val a = points[aIdx]
            val b = points[bIdx]
            if (a.confidence >= Keypoint.MIN_CONFIDENCE &&
                b.confidence >= Keypoint.MIN_CONFIDENCE &&
                isPlausibleBone(aIdx, bIdx, a, b)
            ) {
                connected[aIdx] = true
                connected[bIdx] = true
            }
        }
        return points.mapIndexed { index, keypoint ->
            if (connected[index]) keypoint else keypoint.copy(confidence = 0f)
        }
    }

    private fun isPlausibleBone(aIdx: Int, bIdx: Int, a: Keypoint, b: Keypoint): Boolean {
        if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return false
        if (a.x !in 0f..1f || a.y !in 0f..1f || b.x !in 0f..1f || b.y !in 0f..1f) return false
        val dx = a.x - b.x
        val dy = a.y - b.y
        val distance = sqrt(dx * dx + dy * dy)
        return distance <= maxBoneLength(aIdx, bIdx)
    }

    private fun maxBoneLength(aIdx: Int, bIdx: Int): Float {
        val isFaceEdge = aIdx <= KeypointIndex.RIGHT_EAR && bIdx <= KeypointIndex.RIGHT_EAR
        return when {
            isFaceEdge -> 0.16f
            aIdx == KeypointIndex.LEFT_SHOULDER && bIdx == KeypointIndex.RIGHT_SHOULDER -> 0.42f
            aIdx == KeypointIndex.LEFT_HIP && bIdx == KeypointIndex.RIGHT_HIP -> 0.42f
            else -> 0.38f
        }
    }

    private fun parseHeatmaps(output: Any): List<Keypoint>? {
        val heatmaps = output as? Array<*> ?: return null
        if (heatmaps.size != 1) return null
        val batch = heatmaps[0] as? Array<*> ?: return null

        // Detect NCHW [K, H, W] vs NHWC [H, W, K] by checking if the first
        // dimension equals the joint count. MoveNet from Qualcomm AI Hub
        // exports NCHW heatmaps; NHWC is less common but also handled.
        val format = if (batch.size == KeypointIndex.COUNT) "NCHW" else "NHWC[${batch.size}]"
        if (frameCount <= RAW_OUTPUT_LOG_FRAMES) Log.i(TAG, "Heatmap format detected: $format")
        return if (batch.size == KeypointIndex.COUNT) {
            parseHeatmapsNchw(batch)
        } else {
            parseHeatmapsNhwc(batch)
        }
    }

    /** NCHW: batch[joint][y][x] — MoveNet / Qualcomm AI Hub standard. */
    private fun parseHeatmapsNchw(batch: Array<*>): List<Keypoint>? {
        val joint0 = batch[0] as? Array<*> ?: return null
        val heatmapHeight = joint0.size
        if (heatmapHeight <= 1) return null
        val heatmapWidth = (joint0[0] as? FloatArray)?.size ?: return null
        if (heatmapWidth <= 1) return null

        val points = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        for (joint in 0 until KeypointIndex.COUNT) {
            val jointMap = batch[joint] as? Array<*> ?: continue
            var bestScore = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0 until heatmapHeight) {
                val row = jointMap[y] as? FloatArray ?: continue
                for (x in row.indices) {
                    val score = row[x]
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                }
            }
            val appIndex = MODEL_TO_APP_INDEX[joint]
            points[appIndex] = Keypoint(
                x = bestX.toFloat() / (heatmapWidth - 1).coerceAtLeast(1),
                y = bestY.toFloat() / (heatmapHeight - 1).coerceAtLeast(1),
                confidence = normalizeHeatmapScore(bestScore),
            )
        }
        return points
    }

    /** NHWC: batch[y][x][joint] — channels-last layout. */
    private fun parseHeatmapsNhwc(batch: Array<*>): List<Keypoint>? {
        val heatmapHeight = batch.size
        if (heatmapHeight <= 1) return null
        val firstWidth = batch[0] as? Array<*> ?: return null
        val heatmapWidth = firstWidth.size
        if (heatmapWidth <= 1) return null
        val firstChannels = firstWidth[0] as? FloatArray ?: return null
        if (firstChannels.size < KeypointIndex.COUNT) return null

        val points = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        for (joint in 0 until KeypointIndex.COUNT) {
            var bestScore = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0 until heatmapHeight) {
                val row = batch[y] as? Array<*> ?: continue
                for (x in 0 until heatmapWidth) {
                    val score = (row[x] as? FloatArray)?.getOrNull(joint) ?: continue
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                }
            }
            val appIndex = MODEL_TO_APP_INDEX[joint]
            points[appIndex] = Keypoint(
                x = bestX.toFloat() / (heatmapWidth - 1).coerceAtLeast(1),
                y = bestY.toFloat() / (heatmapHeight - 1).coerceAtLeast(1),
                confidence = normalizeHeatmapScore(bestScore),
            )
        }
        return points
    }

    companion object {
        private const val TAG = "FitForm/LiteRT"
        private const val MODEL_FILE = "movenet_lightning.tflite"
        private const val DEFAULT_INPUT_HEIGHT = 192
        private const val DEFAULT_INPUT_WIDTH = 192
        private const val LOG_INTERVAL_FRAMES = 30
        private const val RAW_OUTPUT_LOG_FRAMES = 8
        // MoveNet outputs [y, x, confidence] per keypoint in normalized [0,1] coords.
        // Heatmap constants are unused for MoveNet (inferCoordinateScale returns 1f).
        private const val MOVENET_HEATMAP_HEIGHT = 48
        private const val MOVENET_HEATMAP_WIDTH = 48
        private const val HEATMAP_TOLERANCE = 2f

        private const val Y_INDEX = 0          // MoveNet: index 0 = y (vertical)
        private const val X_INDEX = 1          // MoveNet: index 1 = x (horizontal)
        private const val CONFIDENCE_INDEX = 2
        private const val DEFAULT_KEYPOINT_CONFIDENCE = 0.5f

        private val MODEL_TO_APP_INDEX = intArrayOf(
            KeypointIndex.NOSE,
            KeypointIndex.LEFT_EYE,
            KeypointIndex.RIGHT_EYE,
            KeypointIndex.LEFT_EAR,
            KeypointIndex.RIGHT_EAR,
            KeypointIndex.LEFT_SHOULDER,
            KeypointIndex.RIGHT_SHOULDER,
            KeypointIndex.LEFT_ELBOW,
            KeypointIndex.RIGHT_ELBOW,
            KeypointIndex.LEFT_WRIST,
            KeypointIndex.RIGHT_WRIST,
            KeypointIndex.LEFT_HIP,
            KeypointIndex.RIGHT_HIP,
            KeypointIndex.LEFT_KNEE,
            KeypointIndex.RIGHT_KNEE,
            KeypointIndex.LEFT_ANKLE,
            KeypointIndex.RIGHT_ANKLE,
        )

        private fun normalizeHeatmapScore(score: Float): Float {
            if (score.isNaN()) return 0f
            if (score in 0f..1f) return score
            val exp = kotlin.math.exp(score)
            return (exp / (1f + exp)).coerceIn(0f, 1f)
        }

        fun tryCreate(context: Context): LiteRtPoseEstimator? {
            val assetExists = runCatching {
                context.assets.open(MODEL_FILE).use { it.read() }
                true
            }.getOrDefault(false)
            if (!assetExists) {
                Log.w(TAG, "$MODEL_FILE not found in assets - caller should use MockPoseEstimator")
                return null
            }

            Log.i(TAG, "Loading $MODEL_FILE from assets (MoveNet via LiteRT)")
            val buffer = FileUtil.loadMappedFile(context, MODEL_FILE)

            var nnApi: NnApiDelegate? = null
            val options = Interpreter.Options().apply { numThreads = 4 }
            var usingNnApi = false
            try {
                nnApi = NnApiDelegate()
                options.addDelegate(nnApi)
                usingNnApi = true
                Log.i(TAG, "NNAPI delegate created - inference will route to Snapdragon NPU when supported")
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI delegate unavailable - falling back to CPU (numThreads=4)", t)
                runCatching { nnApi?.close() }
                nnApi = null
            }

            val interpreter = try {
                try {
                    Interpreter(buffer, options).also {
                        val input = it.getInputTensor(0)
                        val output = it.getOutputTensor(0)
                        Log.i(
                            TAG,
                            "Interpreter ready | backend=${if (usingNnApi) "NNAPI/NPU" else "CPU"} | input=${input.shape().contentToString()} | output=${output.shape().contentToString()} | dtype=${input.dataType()}",
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Interpreter init with NNAPI failed - retrying CPU-only", t)
                    runCatching { nnApi?.close() }
                    nnApi = null
                    usingNnApi = false
                    Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 }).also {
                        Log.i(TAG, "Interpreter ready | backend=CPU (NNAPI fallback)")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$MODEL_FILE is not a valid LiteRT flatbuffer - falling back to MockPoseEstimator.", t)
                runCatching { nnApi?.close() }
                return null
            }

            val estimator = LiteRtPoseEstimator(interpreter, nnApi, usingNnApi)
            estimator.warmup()
            return estimator
        }

        private fun createOutputTemplate(shape: IntArray): Any = when {
            shape.contentEquals(intArrayOf(1, 17, 2)) -> Array(1) { Array(KeypointIndex.COUNT) { FloatArray(2) } }
            shape.contentEquals(intArrayOf(1, 17, 3)) -> Array(1) { Array(KeypointIndex.COUNT) { FloatArray(3) } }
            shape.contentEquals(intArrayOf(1, 1, 17, 3)) -> Array(1) { Array(1) { Array(KeypointIndex.COUNT) { FloatArray(3) } } }
            shape.size == 4 && shape[0] == 1 && shape[3] >= KeypointIndex.COUNT ->
                Array(1) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> Array(1) { Array(KeypointIndex.COUNT) { FloatArray(3) } }
        }
    }
}

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
