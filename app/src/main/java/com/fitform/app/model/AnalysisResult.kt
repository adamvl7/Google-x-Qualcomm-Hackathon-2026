package com.fitform.app.model

/**
 * Per-frame live analysis output. Not serialized to disk directly —
 * the [SessionRecorder] aggregates [FrameData] + cue [SessionEvent]s
 * from these results.
 */
data class AnalysisResult(
    val score: Int,
    val cue: String,
    val severity: Severity,
    val timestampMs: Long,
    val pose: PoseResult,
    /** True when keypoint confidence is so low we should pause scoring. */
    val tracking: Boolean = true,
)
