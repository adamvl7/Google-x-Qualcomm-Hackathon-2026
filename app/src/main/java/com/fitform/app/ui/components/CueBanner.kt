package com.fitform.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitform.app.model.Severity
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

@Composable
fun CueBanner(
    cue: String,
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    val target = severity.color()
    val accent by animateColorAsState(targetValue = target, animationSpec = tween(220), label = "cueAccent")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(FitFormColors.Surface.copy(alpha = 0.92f))
            .border(width = 1.dp, color = accent.copy(alpha = 0.7f), shape = RoundedCornerShape(2.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(28.dp)
                .background(accent)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = severity.label(),
                style = FitFormType.Eyebrow,
                color = accent,
            )
            AnimatedContent(
                targetState = cue,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 2 })
                },
                label = "cueText",
            ) { text ->
                Text(text = text, style = FitFormType.LabelLg, color = FitFormColors.Bone)
            }
        }
    }
}

private fun Severity.color(): Color = when (this) {
    Severity.GREEN -> FitFormColors.StatusGreen
    Severity.YELLOW -> FitFormColors.StatusAmber
    Severity.RED -> FitFormColors.StatusRed
}

private fun Severity.label(): String = when (this) {
    Severity.GREEN -> "ON FORM"
    Severity.YELLOW -> "CAUTION"
    Severity.RED -> "NEEDS WORK"
}
