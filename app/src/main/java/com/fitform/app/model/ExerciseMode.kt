package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExerciseMode(val routeKey: String, val displayLabel: String, val exerciseLabel: String) {
    GYM(routeKey = "gym", displayLabel = "Gym Coach", exerciseLabel = "Squat"),
    SHOT(routeKey = "shot", displayLabel = "Shot Coach", exerciseLabel = "Jump Shot");

    companion object {
        fun fromRouteKey(key: String): ExerciseMode =
            entries.firstOrNull { it.routeKey == key } ?: GYM
    }
}
