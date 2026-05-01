package com.fitform.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitform.app.ui.components.GhostButton
import com.fitform.app.ui.components.OnDeviceBadge
import com.fitform.app.ui.components.PrimaryButton
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

@Composable
fun HomeScreen(
    onGymCoach: () -> Unit,
    onShotCoach: () -> Unit,
    onHistory: () -> Unit,
    onBenchmark: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        // Top frame: brand mark + status badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 14.dp)
                        .background(FitFormColors.Acid)
                )
                Text("FITFORM", style = FitFormType.LabelLg, color = FitFormColors.Bone)
                Text("// 0001", style = FitFormType.Stat, color = FitFormColors.Faint)
            }
            OnDeviceBadge()
        }

        Spacer(Modifier.height(48.dp))

        // Hero
        Text(
            text = "TRAIN",
            style = FitFormType.JerseyNumber,
            color = FitFormColors.Bone,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "SMARTER.",
                style = FitFormType.JerseyNumber,
                color = FitFormColors.Acid,
            )
        }
        Text(
            text = "REAL-TIME COACHING — RUNS LOCAL ON SNAPDRAGON.",
            style = FitFormType.Eyebrow,
            color = FitFormColors.Mute,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(Modifier.height(28.dp))
        TickerRule()
        Spacer(Modifier.height(28.dp))

        // Privacy / framework strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spec(label = "PIPELINE", value = "LITERT")
            Spec(label = "DELEGATE", value = "NPU")
            Spec(label = "CLOUD", value = "NONE")
        }

        Spacer(Modifier.height(40.dp))
        SectionEyebrow("SELECT TRACK")
        Spacer(Modifier.height(16.dp))

        // Mode tiles — visually weighted, asymmetric
        ModeTile(
            number = "01",
            title = "GYM COACH",
            subtitle = "SQUAT · SIDE VIEW",
            description = "Live form, depth, knee tracking, balance.",
            onClick = onGymCoach,
        )
        Spacer(Modifier.height(12.dp))
        ModeTile(
            number = "02",
            title = "SHOT COACH",
            subtitle = "JUMP SHOT · B.E.F.",
            description = "Balance, elbow alignment, follow-through.",
            onClick = onShotCoach,
        )

        Spacer(Modifier.height(32.dp))
        TickerRule()
        Spacer(Modifier.height(20.dp))

        GhostButton(label = "View Session History", onClick = onHistory)
        Spacer(Modifier.height(8.dp))
        GhostButton(label = "Run NPU Benchmark →", onClick = onBenchmark)

        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(40.dp))

        // Footer plate
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "REAL-TIME FEEDBACK · NO CLOUD\nYOUR VIDEOS STAY ON YOUR PHONE.",
                style = FitFormType.Caption,
                color = FitFormColors.Faint,
                textAlign = TextAlign.Start,
            )
            Text(
                text = "v0.1\nTRACK 02",
                style = FitFormType.Stat,
                color = FitFormColors.Faint,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun Spec(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = FitFormType.Eyebrow, color = FitFormColors.Faint)
        Text(value, style = FitFormType.LabelLg, color = FitFormColors.Bone)
    }
}

@Composable
private fun ModeTile(
    number: String,
    title: String,
    subtitle: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(FitFormColors.Surface)
            .clickable { onClick() }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                color = FitFormColors.Acid.copy(alpha = 0.18f),
                start = Offset(size.width * 0.7f, 0f),
                end = Offset(size.width, size.height * 0.55f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
            )
            drawLine(
                color = FitFormColors.Acid.copy(alpha = 0.35f),
                start = Offset(size.width * 0.85f, 0f),
                end = Offset(size.width, size.height * 0.3f),
                strokeWidth = 2f,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(number, style = FitFormType.Stat, color = FitFormColors.Acid)
                Text(subtitle, style = FitFormType.Eyebrow, color = FitFormColors.Mute)
            }
            Column {
                Text(title, style = FitFormType.DisplayMd, color = FitFormColors.Bone)
                Spacer(Modifier.height(6.dp))
                Text(description, style = FitFormType.Body, color = FitFormColors.Mute)
            }
        }
    }
}
