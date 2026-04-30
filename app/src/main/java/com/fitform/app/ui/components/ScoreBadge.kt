package com.fitform.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

/**
 * Hero score readout. Score is the focal element on the live screen —
 * jersey-number scale, with a snappy spring on changes.
 */
@Composable
fun ScoreBadge(
    score: Int,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = score.coerceIn(0, 100)
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "scoreCounter",
    )
    val color = scoreColor(animated, paused)
    val ring by animateColorAsState(targetValue = color, animationSpec = tween(220), label = "ring")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (paused) FitFormColors.SurfaceHigh else FitFormColors.Surface)
                    .border(1.dp, if (paused) FitFormColors.Hairline else ring.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (paused) "TRACKING" else "FORM SCORE",
                    style = FitFormType.Eyebrow,
                    color = if (paused) FitFormColors.Mute else ring,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (paused) "--" else animated.toString(),
                style = FitFormType.JerseyNumber,
                color = if (paused) FitFormColors.Mute else ring,
            )
            Text(
                text = "%",
                style = FitFormType.DisplayMd,
                color = if (paused) FitFormColors.Mute else ring,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

fun scoreColor(score: Int, paused: Boolean = false): Color = when {
    paused -> FitFormColors.Mute
    score >= 90 -> FitFormColors.StatusGreen
    score >= 70 -> FitFormColors.StatusAmber
    else -> FitFormColors.StatusRed
}
