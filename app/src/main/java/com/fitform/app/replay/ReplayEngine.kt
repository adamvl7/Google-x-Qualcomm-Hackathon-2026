package com.fitform.app.replay

import com.fitform.app.model.FrameData
import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.SessionEvent
import com.fitform.app.model.SessionSummary
import com.fitform.app.model.Severity

/**
 * Lookup helpers for syncing the saved skeleton + cues with current
 * playback position. We avoid running pose estimation again at replay
 * time — it's all driven from analysis.json.
 */
class ReplayEngine(private val summary: SessionSummary) {

    private val frames: List<FrameData> = summary.frameData.sortedBy { it.timestampMs }
    val events: List<SessionEvent> = summary.events.sortedBy { it.timestampMs }

    /**
     * All per-frame data at the closest saved timestamp ≤ [positionMs].
     * Returns the exact score, cue, and skeleton that was shown live.
     */
    fun frameAt(positionMs: Long): ReplayFrame {
        if (frames.isEmpty()) return ReplayFrame.EMPTY
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
        return ReplayFrame(
            pose     = PoseResult(timestampMs = frame.timestampMs, keypoints = list),
            score    = frame.score,
            cue      = frame.cue,
            severity = frame.severity,
        )
    }

    /** Rep boundary timestamps for chapter markers on the seek bar. */
    fun repMarkers(): List<Long> = summary.reps.map { it.endTimestampMs }
}

data class ReplayFrame(
    val pose: PoseResult,
    val score: Int,
    val cue: String,
    val severity: Severity,
) {
    companion object {
        val EMPTY = ReplayFrame(PoseResult.EMPTY, 0, "", Severity.GREEN)
    }
}
