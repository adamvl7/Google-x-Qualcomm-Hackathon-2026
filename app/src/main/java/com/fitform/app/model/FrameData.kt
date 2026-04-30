package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
data class FrameData(
    val timestampMs: Long,
    val keypoints: Map<String, Keypoint>,
)

@Serializable
data class SessionEvent(
    val timestampMs: Long,
    val rep: Int,
    val cue: String,
    val severity: Severity,
)
