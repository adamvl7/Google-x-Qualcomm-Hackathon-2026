package com.fitform.app.model

import kotlinx.serialization.Serializable

@Serializable
data class RepData(
    val repNumber: Int,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val score: Int,
    val topCue: String,
)
