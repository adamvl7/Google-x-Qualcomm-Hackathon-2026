package com.fitform.app.model

import kotlinx.serialization.Serializable

/**
 * Top-level shape of analysis.json. Serialized 1:1 to the spec the
 * hackathon submission requires.
 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val mode: String,        // "gym" | "shot"
    val exercise: String,    // "squat" | "jumpshot"
    val createdAt: String,   // ISO-8601 local datetime
    val repCount: Int,
    val averageScore: Int,
    val reps: List<RepData>,
    val frameData: List<FrameData>,
    val events: List<SessionEvent>,
) {
    fun topCues(limit: Int = 3): List<String> =
        events.asSequence()
            .filter { it.severity != Severity.GREEN }
            .groupingBy { it.cue }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toList()
}
