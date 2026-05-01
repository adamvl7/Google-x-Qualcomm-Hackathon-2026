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
import kotlin.math.sqrt

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

        val drawableEdges = KeypointIndex.SKELETON_EDGES.filter { (aIdx, bIdx) ->
            val a = pose[aIdx]
            val b = pose[bIdx]
            a.confidence >= MIN_DRAW_CONFIDENCE &&
                b.confidence >= MIN_DRAW_CONFIDENCE &&
                isPlausibleBone(aIdx, bIdx, a.x, a.y, b.x, b.y)
        }
        val connected = BooleanArray(KeypointIndex.COUNT)
        drawableEdges.forEach { (aIdx, bIdx) ->
            connected[aIdx] = true
            connected[bIdx] = true
        }

        // Bones first
        for ((aIdx, bIdx) in drawableEdges) {
            val a = pose[aIdx]; val b = pose[bIdx]
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
            if (k.confidence < MIN_DRAW_CONFIDENCE || !connected[i]) continue
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

private const val MIN_DRAW_CONFIDENCE = 0.3f

private fun isPlausibleBone(
    aIdx: Int,
    bIdx: Int,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Boolean {
    if (listOf(ax, ay, bx, by).any { it.isNaN() || it !in 0f..1f }) return false
    val dx = ax - bx
    val dy = ay - by
    val distance = sqrt(dx * dx + dy * dy)
    return distance <= maxBoneLength(aIdx, bIdx)
}

private fun maxBoneLength(aIdx: Int, bIdx: Int): Float {
    val face = setOf(
        KeypointIndex.NOSE,
        KeypointIndex.LEFT_EYE,
        KeypointIndex.RIGHT_EYE,
        KeypointIndex.LEFT_EAR,
        KeypointIndex.RIGHT_EAR,
    )
    // Torso and limb bones can span up to ~50% of frame height on a full-body shot;
    // the old 0.38 limit cut off shoulder→hip when the person filled the frame.
    return when {
        aIdx in face && bIdx in face -> 0.18f
        aIdx == KeypointIndex.LEFT_SHOULDER && bIdx == KeypointIndex.RIGHT_SHOULDER -> 0.50f
        aIdx == KeypointIndex.LEFT_HIP && bIdx == KeypointIndex.RIGHT_HIP -> 0.50f
        else -> 0.55f
    }
}
