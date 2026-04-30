package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Keypoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
) {
    val isReliable: Boolean get() = confidence >= MIN_CONFIDENCE

    companion object {
        const val MIN_CONFIDENCE = 0.3f
        val EMPTY = Keypoint(0f, 0f, 0f)
    }
}
