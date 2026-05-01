package com.fitform.app.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitform.app.FitFormApp
import com.fitform.app.model.RepData
import com.fitform.app.model.SessionSummary
import com.fitform.app.ui.components.GhostButton
import com.fitform.app.ui.components.PrimaryButton
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.components.scoreColor
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType
import kotlinx.coroutines.delay

@Composable
fun SetSummaryScreen(
    sessionId: String,
    onWatchReplay: () -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FitFormApp
    val summary by produceState<SessionSummary?>(initialValue = null, sessionId) {
        value = app.sessionRepository.load(sessionId)
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
            Text("SET COMPLETE", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
            Text(sessionId, style = FitFormType.Stat, color = FitFormColors.Faint)
        }

        if (summary == null) {
            Spacer(Modifier.height(64.dp))
            Text("Loading summary…", style = FitFormType.BodyLg, color = FitFormColors.Mute)
            return@Column
        }

        val s = summary!!
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (s.mode == "gym") "SQUAT" else "JUMP SHOT",
            style = FitFormType.DisplayHero,
            color = FitFormColors.Bone,
        )
        Text(s.createdAt, style = FitFormType.Caption, color = FitFormColors.Mute)

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            HeroStat(
                eyebrow = "AVG FORM",
                value = "${s.averageScore}",
                suffix = "%",
                accent = scoreColor(s.averageScore),
                modifier = Modifier.weight(1f),
            )
            HeroStat(
                eyebrow = "REPS",
                value = s.repCount.toString().padStart(2, '0'),
                suffix = null,
                accent = FitFormColors.Bone,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(28.dp))
        TickerRule()
        Spacer(Modifier.height(20.dp))
        SectionEyebrow("PER-REP BREAKDOWN")
        Spacer(Modifier.height(12.dp))

        s.reps.forEach { rep ->
            RepRow(rep)
            Spacer(Modifier.height(8.dp))
        }

        if (s.reps.isEmpty()) {
            Text(
                "No reps were captured for this set.",
                style = FitFormType.Body,
                color = FitFormColors.Mute,
            )
        }

        val cues = s.topCues()
        if (cues.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionEyebrow("TOP CORRECTIONS")
            Spacer(Modifier.height(12.dp))
            cues.forEachIndexed { idx, cue ->
                CueRow(idx = idx + 1, cue = cue)
                if (idx < cues.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        GemmaCoachCard(context = context, summary = s)

        Spacer(Modifier.height(36.dp))
        PrimaryButton(label = "Watch Replay", eyebrow = "REVIEW WITH OVERLAY", onClick = onWatchReplay)
        Spacer(Modifier.height(12.dp))
        GhostButton(label = "Back to Home", onClick = onHome)
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun HeroStat(eyebrow: String, value: String, suffix: String?, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier
        .background(FitFormColors.Surface)
        .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(eyebrow, style = FitFormType.Eyebrow, color = FitFormColors.Mute)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = FitFormType.JerseyNumber, color = accent)
            if (suffix != null) {
                Text(suffix, style = FitFormType.DisplayMd, color = accent, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
            }
        }
    }
}

@Composable
private fun RepRow(rep: RepData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "#${rep.repNumber.toString().padStart(2, '0')}",
            style = FitFormType.Stat,
            color = FitFormColors.Mute,
        )
        Text(rep.topCue, style = FitFormType.LabelLg, color = FitFormColors.Bone, modifier = Modifier.weight(1f))
        Text(
            text = "${rep.score}%",
            style = FitFormType.DisplayMd,
            color = scoreColor(rep.score),
        )
    }
}

private enum class GemmaState { CHECKING, LOADING, TYPING, DONE, UNAVAILABLE }

@Composable
private fun GemmaCoachCard(context: android.content.Context, summary: SessionSummary) {
    var state by remember { mutableStateOf(GemmaState.CHECKING) }
    var displayedText by remember { mutableStateOf("") }
    var fullText by remember { mutableStateOf("") }

    LaunchedEffect(summary.sessionId) {
        if (!GemmaCoach.isAvailable(context)) {
            state = GemmaState.UNAVAILABLE
            return@LaunchedEffect
        }
        state = GemmaState.LOADING
        val result = GemmaCoach.generate(context, summary)
        if (result == null) {
            state = GemmaState.UNAVAILABLE
            return@LaunchedEffect
        }
        fullText = result
        state = GemmaState.TYPING
        for (i in result.indices) {
            displayedText = result.substring(0, i + 1)
            delay(18)
        }
        state = GemmaState.DONE
    }

    SectionEyebrow("AI COACHING")
    Spacer(Modifier.height(12.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when (state) {
                            GemmaState.LOADING, GemmaState.TYPING -> FitFormColors.StatusAmber
                            GemmaState.DONE -> FitFormColors.Acid
                            else -> FitFormColors.Faint
                        }
                    ),
            )
            Text(
                text = when (state) {
                    GemmaState.CHECKING, GemmaState.LOADING -> "GEMMA · GENERATING"
                    GemmaState.TYPING, GemmaState.DONE -> "GEMMA · ON-DEVICE"
                    GemmaState.UNAVAILABLE -> "GEMMA · OFFLINE"
                },
                style = FitFormType.Eyebrow,
                color = when (state) {
                    GemmaState.LOADING, GemmaState.TYPING -> FitFormColors.StatusAmber
                    GemmaState.DONE -> FitFormColors.Acid
                    else -> FitFormColors.Faint
                },
            )
        }

        when (state) {
            GemmaState.CHECKING, GemmaState.LOADING -> {
                Text(
                    text = "Analysing your set…",
                    style = FitFormType.Body,
                    color = FitFormColors.Mute,
                )
            }
            GemmaState.TYPING -> {
                Text(
                    text = "$displayedText▋",
                    style = FitFormType.BodyLg,
                    color = FitFormColors.Bone,
                )
            }
            GemmaState.DONE -> {
                Text(
                    text = fullText,
                    style = FitFormType.BodyLg,
                    color = FitFormColors.Bone,
                )
            }
            GemmaState.UNAVAILABLE -> {
                Text(
                    text = "Push the Gemma model to enable on-device AI coaching.",
                    style = FitFormType.Body,
                    color = FitFormColors.Mute,
                )
                Text(
                    text = "adb push gemma3-1B-it-int4.task /sdcard/Android/data/com.fitform.app/files/gemma/",
                    style = FitFormType.Caption,
                    color = FitFormColors.Faint,
                )
            }
        }
    }
}

@Composable
private fun CueRow(idx: Int, cue: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = idx.toString().padStart(2, '0'),
            style = FitFormType.Stat,
            color = FitFormColors.Acid,
        )
        Text(cue, style = FitFormType.LabelLg, color = FitFormColors.Bone)
    }
}
