package com.fitform.app.pose

import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import kotlin.math.abs

/**
 * Stabilizes pose landmarks before scoring/recording so replay overlays do not
 * flicker when a single frame has a noisy or low-confidence joint.
 */
class PoseSmoother {
    private var previous: PoseResult? = null
    private val heldFrames = IntArray(KeypointIndex.COUNT)

    fun smooth(raw: PoseResult): PoseResult {
        val last = previous
        if (last == null || raw.keypoints.size < KeypointIndex.COUNT) {
            previous = raw
            heldFrames.fill(0)
            return raw
        }

        val smoothed = List(KeypointIndex.COUNT) { index ->
            val current = raw[index]
            val prior = last[index]
            when {
                current.confidence < HOLD_CONFIDENCE && prior.confidence >= Keypoint.MIN_CONFIDENCE && heldFrames[index] < MAX_HOLD_FRAMES -> {
                    heldFrames[index] += 1
                    prior.copy(confidence = (prior.confidence * 0.82f).coerceAtLeast(0f))
                }
                current.confidence < Keypoint.MIN_CONFIDENCE -> {
                    heldFrames[index] = 0
                    current
                }
                jumpedTooFar(current, prior) && prior.confidence >= Keypoint.MIN_CONFIDENCE -> {
                    heldFrames[index] = 0
                    blend(current, prior, JUMP_ALPHA)
                }
                else -> {
                    heldFrames[index] = 0
                    blend(current, prior, NORMAL_ALPHA)
                }
            }
        }

        return PoseResult(timestampMs = raw.timestampMs, keypoints = smoothed).also {
            previous = it
        }
    }

    fun reset() {
        previous = null
        heldFrames.fill(0)
    }

    private fun blend(current: Keypoint, prior: Keypoint, alpha: Float): Keypoint {
        if (prior.confidence <= 0f) return current
        val retain = 1f - alpha
        return Keypoint(
            x = prior.x * retain + current.x * alpha,
            y = prior.y * retain + current.y * alpha,
            confidence = maxOf(current.confidence, prior.confidence * 0.92f),
        )
    }

    private fun jumpedTooFar(current: Keypoint, prior: Keypoint): Boolean =
        abs(current.x - prior.x) > MAX_FRAME_JUMP || abs(current.y - prior.y) > MAX_FRAME_JUMP

    companion object {
        private const val NORMAL_ALPHA = 0.35f
        private const val JUMP_ALPHA = 0.15f
        private const val HOLD_CONFIDENCE = 0.20f
        private const val MAX_HOLD_FRAMES = 4
        private const val MAX_FRAME_JUMP = 0.16f
    }
}
