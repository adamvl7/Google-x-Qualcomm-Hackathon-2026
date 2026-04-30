package com.fitform.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

/**
 * The "Tactical Performance" button language: sharp 2dp corners,
 * jersey-style label type, no Material chrome. Variants for
 * primary CTAs, ghost buttons, and live-state action triggers.
 */

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    eyebrow: String? = null,
) {
    val bg = if (enabled) FitFormColors.Acid else FitFormColors.SurfaceHigh
    val fg = if (enabled) FitFormColors.Ink else FitFormColors.Mute
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (eyebrow != null) {
                Text(eyebrow, style = FitFormType.Eyebrow, color = fg.copy(alpha = 0.6f))
            }
            Text(label.uppercase(), style = FitFormType.LabelLg, color = fg)
        }
        Text("→", style = FitFormType.DisplayMd, color = fg)
    }
}

@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val fg = if (enabled) FitFormColors.Bone else FitFormColors.Mute
    Row(
        modifier = modifier
            .let { if (fullWidth) it.fillMaxWidth() else it }
            .height(56.dp)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, FitFormColors.Hairline, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .background(FitFormColors.Acid)
        )
        Text(label.uppercase(), style = FitFormType.LabelLg, color = fg)
    }
}

@Composable
fun LiveActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = FitFormColors.Bone,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = if (filled) accent else Color.Transparent
    val fg = if (filled) FitFormColors.Ink else accent
    val border = if (filled) accent else accent.copy(alpha = 0.55f)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = FitFormType.LabelLg, color = if (enabled) fg else FitFormColors.Mute)
    }
}
