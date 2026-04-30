package com.fitform.app.analysis

import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Side
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

object GeometryUtils {

    /**
     * Angle (in degrees) at vertex [b] formed by rays b→a and b→c.
     * Returns 0..180.
     */
    fun angleBetweenThreePoints(a: Keypoint, b: Keypoint, c: Keypoint): Float {
        val ax = a.x - b.x; val ay = a.y - b.y
        val cx = c.x - b.x; val cy = c.y - b.y
        val angleA = atan2(ay, ax)
        val angleC = atan2(cy, cx)
        var diff = (angleA - angleC) * 180f / PI.toFloat()
        if (diff < 0) diff = -diff
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    fun distanceBetweenPoints(a: Keypoint, b: Keypoint): Float =
        hypot(a.x - b.x, a.y - b.y)

    /** Signed: positive when [b] is to the right of [a]. */
    fun horizontalOffset(a: Keypoint, b: Keypoint): Float = b.x - a.x

    /** Signed: positive when [b] is BELOW [a] (image coords grow downward). */
    fun verticalOffset(a: Keypoint, b: Keypoint): Float = b.y - a.y

    fun confidenceAverage(keypoints: List<Keypoint>): Float =
        if (keypoints.isEmpty()) 0f else keypoints.map { it.confidence }.average().toFloat()

    /**
     * Sanity check: do we see enough of the body to do side-view analysis?
     */
    fun sideViewBodyVisibilityCheck(pose: PoseResult): Boolean {
        val core = listOf(
            KeypointIndex.LEFT_HIP, KeypointIndex.RIGHT_HIP,
            KeypointIndex.LEFT_KNEE, KeypointIndex.RIGHT_KNEE,
            KeypointIndex.LEFT_ANKLE, KeypointIndex.RIGHT_ANKLE,
            KeypointIndex.LEFT_SHOULDER, KeypointIndex.RIGHT_SHOULDER,
        ).map { pose[it] }
        val avg = confidenceAverage(core)
        // In side view one side is occluded — the far-side joints will always score
        // low. Threshold here is intentionally permissive (0.20); per-side confidence
        // guards in each analyzer apply the tighter check on the visible side.
        return avg >= 0.20f
    }

    /** Pick whichever side (LEFT/RIGHT) has higher avg confidence on the leg+torso chain. */
    fun selectBetterSide(pose: PoseResult): Side {
        fun chain(side: Side): Float {
            val ids = if (side == Side.LEFT) listOf(
                KeypointIndex.LEFT_SHOULDER, KeypointIndex.LEFT_HIP,
                KeypointIndex.LEFT_KNEE, KeypointIndex.LEFT_ANKLE,
                KeypointIndex.LEFT_ELBOW, KeypointIndex.LEFT_WRIST,
            ) else listOf(
                KeypointIndex.RIGHT_SHOULDER, KeypointIndex.RIGHT_HIP,
                KeypointIndex.RIGHT_KNEE, KeypointIndex.RIGHT_ANKLE,
                KeypointIndex.RIGHT_ELBOW, KeypointIndex.RIGHT_WRIST,
            )
            return confidenceAverage(ids.map { pose[it] })
        }
        return if (chain(Side.LEFT) >= chain(Side.RIGHT)) Side.LEFT else Side.RIGHT
    }

    fun absHorizontalOffset(a: Keypoint, b: Keypoint): Float = abs(b.x - a.x)
    fun absVerticalOffset(a: Keypoint, b: Keypoint): Float = abs(b.y - a.y)

    /**
     * Indices of shoulder, hip, knee, ankle, elbow, wrist for the
     * selected [side]. Returned in a stable order useful for analyzers.
     */
    data class SideIndices(
        val shoulder: Int, val hip: Int, val knee: Int, val ankle: Int,
        val elbow: Int, val wrist: Int,
    )

    fun indicesFor(side: Side): SideIndices = if (side == Side.LEFT) {
        SideIndices(
            shoulder = KeypointIndex.LEFT_SHOULDER, hip = KeypointIndex.LEFT_HIP,
            knee = KeypointIndex.LEFT_KNEE, ankle = KeypointIndex.LEFT_ANKLE,
            elbow = KeypointIndex.LEFT_ELBOW, wrist = KeypointIndex.LEFT_WRIST,
        )
    } else {
        SideIndices(
            shoulder = KeypointIndex.RIGHT_SHOULDER, hip = KeypointIndex.RIGHT_HIP,
            knee = KeypointIndex.RIGHT_KNEE, ankle = KeypointIndex.RIGHT_ANKLE,
            elbow = KeypointIndex.RIGHT_ELBOW, wrist = KeypointIndex.RIGHT_WRIST,
        )
    }
}
