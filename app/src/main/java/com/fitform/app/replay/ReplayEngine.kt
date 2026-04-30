package com.fitform.app.replay

import com.fitform.app.model.FrameData
import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.SessionEvent
import com.fitform.app.model.SessionSummary

/**
 * Lookup helpers for syncing the saved skeleton + cues with current
 * playback position. We avoid running pose estimation again at replay
 * time — it's all driven from analysis.json.
 */
class ReplayEngine(private val summary: SessionSummary) {

    private val frames: List<FrameData> = summary.frameData.sortedBy { it.timestampMs }
    val events: List<SessionEvent> = summary.events.sortedBy { it.timestampMs }

    /** Skeleton at the closest saved timestamp ≤ [positionMs]. */
    fun poseAt(positionMs: Long): PoseResult {
        if (frames.isEmpty()) return PoseResult.EMPTY
        var lo = 0; var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (frames[mid].timestampMs <= positionMs) lo = mid else hi = mid - 1
        }
        val frame = frames[lo]
        val list = MutableList(KeypointIndex.COUNT) { Keypoint.EMPTY }
        for ((name, kp) in frame.keypoints) {
            val idx = KeypointIndex.NAMES.indexOf(name)
            if (idx in 0 until KeypointIndex.COUNT) list[idx] = kp
        }
        return PoseResult(timestampMs = frame.timestampMs, keypoints = list)
    }

    /** The most recent event before [positionMs], if any (within 2s). */
    fun activeEvent(positionMs: Long): SessionEvent? {
        val candidate = events.lastOrNull { it.timestampMs <= positionMs } ?: return null
        return if (positionMs - candidate.timestampMs <= 2000L) candidate else null
    }

    /** Rep boundary timestamps for chapter markers on the seek bar. */
    fun repMarkers(): List<Long> = summary.reps.map { it.endTimestampMs }
}
