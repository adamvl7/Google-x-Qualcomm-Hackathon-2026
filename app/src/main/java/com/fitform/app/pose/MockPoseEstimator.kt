package com.fitform.app.pose

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a believable side-view skeleton that animates through the
 * target movement, so the UI can be developed without the LiteRT
 * runtime. Only used when [LiteRtPoseEstimator] fails to load
 * (for example when the LiteHRNet asset isn't in assets).
 *
 * The phase parameter is derived from wall clock so cues progress
 * naturally even if no rep boundaries are tapped.
 */
class MockPoseEstimator(private val mode: ExerciseMode) : PoseEstimator {

    override suspend fun estimatePose(frame: ImageProxy): PoseResult? {
        val timestamp = SystemClock.elapsedRealtime()
        frame.close()
        val phase = ((timestamp % CYCLE_MS).toFloat() / CYCLE_MS) * (2 * PI).toFloat()
        val pose = when (mode) {
            ExerciseMode.GYM -> squatPose(phase)
            ExerciseMode.SHOT -> shotPose(phase)
        }
        return PoseResult(timestampMs = timestamp, keypoints = pose)
    }

    override fun close() = Unit

    private fun squatPose(phase: Float): List<Keypoint> {
        val depth = (1f - cos(phase)) / 2f
        val list = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        val baseX = 0.55f
        list[KeypointIndex.NOSE] = kp(baseX, 0.18f + depth * 0.08f)
        list[KeypointIndex.LEFT_EYE] = kp(baseX - 0.012f, 0.175f + depth * 0.08f)
        list[KeypointIndex.RIGHT_EYE] = kp(baseX + 0.012f, 0.175f + depth * 0.08f)
        list[KeypointIndex.LEFT_SHOULDER] = kp(baseX - 0.045f, 0.30f + depth * 0.10f, 0.94f)
        list[KeypointIndex.RIGHT_SHOULDER] = kp(baseX + 0.012f, 0.30f + depth * 0.10f, 0.91f)
        list[KeypointIndex.LEFT_HIP] = kp(baseX - 0.04f, 0.55f + depth * 0.07f, 0.92f)
        list[KeypointIndex.RIGHT_HIP] = kp(baseX + 0.012f, 0.55f + depth * 0.07f, 0.90f)
        list[KeypointIndex.LEFT_KNEE] = kp(baseX - 0.025f + depth * 0.04f, 0.72f + depth * 0.04f, 0.89f)
        list[KeypointIndex.RIGHT_KNEE] = kp(baseX + 0.04f + depth * 0.04f, 0.72f + depth * 0.04f, 0.86f)
        list[KeypointIndex.LEFT_ANKLE] = kp(baseX - 0.045f, 0.92f, 0.88f)
        list[KeypointIndex.RIGHT_ANKLE] = kp(baseX + 0.025f, 0.92f, 0.86f)
        list[KeypointIndex.LEFT_ELBOW] = kp(baseX - 0.10f, 0.42f + depth * 0.10f, 0.80f)
        list[KeypointIndex.RIGHT_ELBOW] = kp(baseX + 0.06f, 0.42f + depth * 0.10f, 0.78f)
        list[KeypointIndex.LEFT_WRIST] = kp(baseX - 0.16f, 0.50f + depth * 0.12f, 0.74f)
        list[KeypointIndex.RIGHT_WRIST] = kp(baseX + 0.10f, 0.50f + depth * 0.12f, 0.72f)
        return list
    }

    private fun shotPose(phase: Float): List<Keypoint> {
        // 0..π = load + jump, π..2π = release + descent
        val rise = sin(phase).coerceAtLeast(0f)
        val load = (1f - cos(phase)) / 2f
        val list = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        val baseX = 0.50f
        val verticalOffset = -rise * 0.10f
        list[KeypointIndex.NOSE] = kp(baseX, 0.20f + verticalOffset)
        list[KeypointIndex.LEFT_SHOULDER] = kp(baseX - 0.04f, 0.32f + verticalOffset, 0.93f)
        list[KeypointIndex.RIGHT_SHOULDER] = kp(baseX + 0.02f, 0.32f + verticalOffset, 0.92f)
        list[KeypointIndex.LEFT_HIP] = kp(baseX - 0.04f, 0.55f + verticalOffset, 0.91f)
        list[KeypointIndex.RIGHT_HIP] = kp(baseX + 0.01f, 0.55f + verticalOffset, 0.90f)
        list[KeypointIndex.LEFT_KNEE] = kp(baseX - 0.03f, 0.72f + load * 0.04f + verticalOffset, 0.88f)
        list[KeypointIndex.RIGHT_KNEE] = kp(baseX + 0.03f, 0.72f + load * 0.04f + verticalOffset, 0.86f)
        list[KeypointIndex.LEFT_ANKLE] = kp(baseX - 0.04f, 0.92f + verticalOffset * 0.5f, 0.87f)
        list[KeypointIndex.RIGHT_ANKLE] = kp(baseX + 0.03f, 0.92f + verticalOffset * 0.5f, 0.85f)
        // shooting (right) arm extending up across the cycle
        val release = sin(phase).coerceAtLeast(0f)
        list[KeypointIndex.RIGHT_ELBOW] = kp(baseX + 0.05f, 0.30f + verticalOffset - release * 0.08f, 0.86f)
        list[KeypointIndex.RIGHT_WRIST] = kp(baseX + 0.07f, 0.20f + verticalOffset - release * 0.18f, 0.83f)
        // guide (left) arm
        list[KeypointIndex.LEFT_ELBOW] = kp(baseX - 0.06f, 0.40f + verticalOffset, 0.75f)
        list[KeypointIndex.LEFT_WRIST] = kp(baseX - 0.02f, 0.30f + verticalOffset - release * 0.10f, 0.70f)
        return list
    }

    private fun kp(x: Float, y: Float, c: Float = 0.92f) = Keypoint(x, y, c)

    companion object {
        private const val CYCLE_MS = 2400L
    }
}
