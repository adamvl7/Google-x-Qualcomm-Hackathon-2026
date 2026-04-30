package com.fitform.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Severity
import com.fitform.app.ui.theme.FitFormColors

/**
 * Skeleton overlay drawn over a camera or video frame.
 * Coords are normalized [0,1]; we scale to the canvas size.
 *
 * The fill color is driven by the active feedback severity so the
 * skeleton itself communicates state, in addition to the cue banner.
 */
@Composable
fun PoseOverlay(
    pose: PoseResult,
    severity: Severity,
    modifier: Modifier = Modifier,
    mirror: Boolean = true,
) {
    val accent = when (severity) {
        Severity.GREEN -> FitFormColors.StatusGreen
        Severity.YELLOW -> FitFormColors.StatusAmber
        Severity.RED -> FitFormColors.StatusRed
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun map(idx: Int): Offset {
            val k = pose[idx]
            val x = if (mirror) (1f - k.x) else k.x
            return Offset(x * w, k.y * h)
        }

        // Bones first
        for ((aIdx, bIdx) in KeypointIndex.SKELETON_EDGES) {
            val a = pose[aIdx]; val b = pose[bIdx]
            if (a.confidence < 0.3f || b.confidence < 0.3f) continue
            val avg = (a.confidence + b.confidence) / 2f
            drawLine(
                color = accent.copy(alpha = (0.45f + 0.55f * avg).coerceAtMost(1f)),
                start = map(aIdx),
                end = map(bIdx),
                strokeWidth = 5f,
            )
        }

        // Joints on top
        for (i in 0 until KeypointIndex.COUNT) {
            val k = pose[i]
            if (k.confidence < 0.3f) continue
            // Outer halo
            drawCircle(
                color = accent.copy(alpha = 0.35f),
                radius = 11f,
                center = map(i),
            )
            // Inner core
            drawCircle(
                color = Color.White,
                radius = 4.5f,
                center = map(i),
            )
        }
    }
}
