package com.fitform.app.ui.setup

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
import androidx.compose.ui.unit.dp
import com.fitform.app.model.ExerciseMode
import com.fitform.app.ui.components.PrimaryButton
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

@Composable
fun SetupScreen(
    mode: ExerciseMode,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val title = when (mode) {
        ExerciseMode.GYM -> "SQUAT SETUP"
        ExerciseMode.SHOT -> "JUMP SHOT SETUP"
    }
    val instruction = when (mode) {
        ExerciseMode.GYM -> "Place your phone 6–8 feet away at hip height. Stand sideways so your full body is visible."
        ExerciseMode.SHOT -> "Place your phone 6–8 feet directly in front of you. Face the camera so your full body, both arms, and knees are visible."
    }
    val checks = when (mode) {
        ExerciseMode.GYM -> listOf(
            "Side-on view of your full body" to "Hip · knee · ankle stack must be visible",
            "Phone at hip height" to "Stable surface — tripod, bench, gym bag",
            "Even lighting, no backlight" to "Avoid windows directly behind you",
        )
        ExerciseMode.SHOT -> listOf(
            "Face the camera directly" to "Both shoulders · elbows · wrists in frame",
            "Full body visible head to toe" to "Knees and feet must be in frame",
            "Even lighting, no backlight" to "Gym lights overhead are ideal",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.clickable { onBack() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("←", style = FitFormType.DisplayMd, color = FitFormColors.Bone)
                Text("BACK", style = FitFormType.LabelLg, color = FitFormColors.Mute)
            }
            Text("STEP 01 / 02", style = FitFormType.Stat, color = FitFormColors.Faint)
        }

        Spacer(Modifier.height(36.dp))

        Text(mode.displayLabel.uppercase(), style = FitFormType.Eyebrow, color = FitFormColors.Acid)
        Spacer(Modifier.height(8.dp))
        Text(title, style = FitFormType.DisplayHero, color = FitFormColors.Bone)

        Spacer(Modifier.height(24.dp))

        // Instructional diagram block
        StancePictogram(mode = mode)

        Spacer(Modifier.height(24.dp))
        Text(instruction, style = FitFormType.BodyLg, color = FitFormColors.Bone)

        Spacer(Modifier.height(28.dp))
        TickerRule()
        Spacer(Modifier.height(20.dp))
        SectionEyebrow("CHECKLIST")
        Spacer(Modifier.height(12.dp))

        checks.forEachIndexed { idx, (label, helper) ->
            ChecklistItem(idx = idx + 1, label = label, helper = helper)
            if (idx < checks.lastIndex) Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(36.dp))
        PrimaryButton(
            label = "Start Camera",
            eyebrow = "STEP 02",
            onClick = onStart,
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Pose data is processed on-device with LiteRT.",
            style = FitFormType.Caption,
            color = FitFormColors.Faint,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ChecklistItem(idx: Int, label: String, helper: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(FitFormColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(idx.toString().padStart(2, '0'), style = FitFormType.Stat, color = FitFormColors.Acid)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(label, style = FitFormType.LabelLg, color = FitFormColors.Bone)
            Text(helper, style = FitFormType.Body, color = FitFormColors.Mute)
        }
    }
}

@Composable
private fun StancePictogram(mode: ExerciseMode) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(FitFormColors.Surface),
    ) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)) {
            val w = size.width
            val h = size.height

            // grid backdrop
            val gridColor = FitFormColors.Hairline
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                )
            }

            // pictogram on the right side: simple stick figure
            val cx = w * 0.78f
            val accent = FitFormColors.Acid
            // head
            drawCircle(color = accent, radius = 8f, center = Offset(cx, h * 0.15f))
            // torso
            drawLine(accent, Offset(cx, h * 0.18f), Offset(cx, h * 0.55f), strokeWidth = 3f)
            when (mode) {
                ExerciseMode.GYM -> {
                    // arms forward
                    drawLine(accent, Offset(cx, h * 0.30f), Offset(cx + 30f, h * 0.30f), strokeWidth = 3f)
                    // bent legs (squat)
                    drawLine(accent, Offset(cx, h * 0.55f), Offset(cx + 25f, h * 0.75f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx + 25f, h * 0.75f), Offset(cx + 5f, h * 0.95f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx, h * 0.55f), Offset(cx - 18f, h * 0.78f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx - 18f, h * 0.78f), Offset(cx - 30f, h * 0.95f), strokeWidth = 3f)
                }
                ExerciseMode.SHOT -> {
                    // shooting arm up
                    drawLine(accent, Offset(cx, h * 0.25f), Offset(cx + 15f, h * 0.10f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx + 15f, h * 0.10f), Offset(cx + 25f, h * 0.02f), strokeWidth = 3f)
                    drawCircle(accent, radius = 6f, center = Offset(cx + 32f, h * 0.0f))
                    // guide arm
                    drawLine(accent, Offset(cx, h * 0.30f), Offset(cx + 12f, h * 0.20f), strokeWidth = 3f)
                    // legs (bent)
                    drawLine(accent, Offset(cx, h * 0.55f), Offset(cx + 10f, h * 0.78f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx + 10f, h * 0.78f), Offset(cx + 5f, h * 0.95f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx, h * 0.55f), Offset(cx - 12f, h * 0.78f), strokeWidth = 3f)
                    drawLine(accent, Offset(cx - 12f, h * 0.78f), Offset(cx - 18f, h * 0.95f), strokeWidth = 3f)
                }
            }

            // phone bracket on the left
            val px = w * 0.20f
            drawLine(FitFormColors.Bone, Offset(px - 18f, h * 0.30f), Offset(px + 18f, h * 0.30f), strokeWidth = 2f)
            drawLine(FitFormColors.Bone, Offset(px - 18f, h * 0.30f), Offset(px - 18f, h * 0.70f), strokeWidth = 2f)
            drawLine(FitFormColors.Bone, Offset(px + 18f, h * 0.30f), Offset(px + 18f, h * 0.70f), strokeWidth = 2f)
            drawLine(FitFormColors.Bone, Offset(px - 18f, h * 0.70f), Offset(px + 18f, h * 0.70f), strokeWidth = 2f)
            // sight cone
            drawLine(
                color = accent.copy(alpha = 0.5f),
                start = Offset(px + 18f, h * 0.50f),
                end = Offset(cx - 30f, h * 0.20f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
            )
            drawLine(
                color = accent.copy(alpha = 0.5f),
                start = Offset(px + 18f, h * 0.50f),
                end = Offset(cx - 10f, h * 0.92f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
            )
        }
        Text(
            text = "6–8 FT",
            style = FitFormType.Stat,
            color = FitFormColors.Mute,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}
