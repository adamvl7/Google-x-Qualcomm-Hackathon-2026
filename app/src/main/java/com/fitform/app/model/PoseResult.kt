package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
data class PoseResult(
    val timestampMs: Long,
    val keypoints: List<Keypoint>,
) {
    operator fun get(index: Int): Keypoint = keypoints.getOrNull(index) ?: Keypoint.EMPTY

    val averageConfidence: Float
        get() = if (keypoints.isEmpty()) 0f else keypoints.map { it.confidence }.average().toFloat()

    fun coreVisible(): Boolean {
        val core = listOf(
            KeypointIndex.LEFT_SHOULDER, KeypointIndex.RIGHT_SHOULDER,
            KeypointIndex.LEFT_HIP, KeypointIndex.RIGHT_HIP,
            KeypointIndex.LEFT_KNEE, KeypointIndex.RIGHT_KNEE,
            KeypointIndex.LEFT_ANKLE, KeypointIndex.RIGHT_ANKLE,
        )
        val avg = core.map { get(it).confidence }.average().toFloat()
        return avg >= 0.35f
    }

    companion object {
        val EMPTY = PoseResult(0L, List(KeypointIndex.COUNT) { Keypoint.EMPTY })
    }
}

enum class Side { LEFT, RIGHT }
