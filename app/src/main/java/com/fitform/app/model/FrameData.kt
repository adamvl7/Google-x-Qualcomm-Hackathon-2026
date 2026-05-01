package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
data class FrameData(
    val timestampMs: Long,
    val keypoints: Map<String, Keypoint>,
    // Per-frame stats stored at recording time so replay shows the exact
    // same score and cue the user saw live — no re-inference required.
    val score: Int = 0,
    val cue: String = "",
    val severity: Severity = Severity.GREEN,
)

@Serializable
data class SessionEvent(
    val timestampMs: Long,
    val rep: Int,
    val cue: String,
    val severity: Severity,
)
