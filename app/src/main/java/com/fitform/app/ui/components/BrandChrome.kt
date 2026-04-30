package com.fitform.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

/**
 * Reusable brand chrome — used across screens to create cohesion.
 */

@Composable
fun OnDeviceBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(FitFormColors.Surface)
            .border(1.dp, FitFormColors.Acid, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(FitFormColors.Acid)
        )
        Text("ON-DEVICE", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
    }
}

@Composable
fun TickerRule(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = FitFormColors.Hairline) {
    Canvas(modifier = modifier
        .fillMaxWidth()
        .height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
        )
    }
}

@Composable
fun CornerNotch(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = FitFormColors.Acid) {
    Canvas(modifier = modifier.size(28.dp)) {
        // Diagonal accent stripe in a corner — broadcast graphics signature.
        drawLine(
            color = color,
            start = Offset(0f, size.height * 0.6f),
            end = Offset(size.width * 0.6f, 0f),
            strokeWidth = 2f,
        )
        drawLine(
            color = color,
            start = Offset(0f, size.height * 0.85f),
            end = Offset(size.width * 0.85f, 0f),
            strokeWidth = 2f,
        )
    }
}

@Composable
fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(FitFormColors.Acid)
        )
        Text(text, style = FitFormType.Eyebrow, color = FitFormColors.Mute)
    }
}
