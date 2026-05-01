package com.fitform.app.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
