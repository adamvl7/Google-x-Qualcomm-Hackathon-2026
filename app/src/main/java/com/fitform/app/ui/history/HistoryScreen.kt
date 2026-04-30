package com.fitform.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitform.app.FitFormApp
import com.fitform.app.model.SessionSummary
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.components.scoreColor
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FitFormApp
    val sessions by produceState<List<SessionSummary>>(initialValue = emptyList()) {
        value = app.sessionRepository.listSummaries()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink),
    ) {
        Column(modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 56.dp, bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.clickable { onBack() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("←", style = FitFormType.DisplayMd, color = FitFormColors.Bone)
                    Text("BACK", style = FitFormType.LabelLg, color = FitFormColors.Mute)
                }
                Text("ARCHIVE", style = FitFormType.Stat, color = FitFormColors.Faint)
            }
            Spacer(Modifier.height(24.dp))
            Text("HISTORY", style = FitFormType.DisplayHero, color = FitFormColors.Bone)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${sessions.size} SESSION${if (sessions.size == 1) "" else "S"} ON-DEVICE",
                style = FitFormType.Eyebrow,
                color = FitFormColors.Mute,
            )
            Spacer(Modifier.height(20.dp))
            TickerRule()
        }

        if (sessions.isEmpty()) {
            EmptyState(modifier = Modifier
                .fillMaxSize()
                .padding(32.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    SectionEyebrow("MOST RECENT")
                    Spacer(Modifier.height(8.dp))
                }
                items(sessions, key = { it.sessionId }) { summary ->
                    SessionCard(summary = summary, onClick = { onOpenSession(summary.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun SessionCard(summary: SessionSummary, onClick: () -> Unit) {
    val title = if (summary.mode == "gym") "SQUAT" else "JUMP SHOT"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = FitFormType.LabelLg, color = FitFormColors.Bone)
                Text("· ${summary.repCount} REPS", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
            }
            Text(summary.createdAt, style = FitFormType.Caption, color = FitFormColors.Mute)
            val cue = summary.topCues(1).firstOrNull() ?: "Good rep"
            Text(cue, style = FitFormType.Body, color = FitFormColors.Bone)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${summary.averageScore}%", style = FitFormType.DisplayMd, color = scoreColor(summary.averageScore))
            Text("AVG", style = FitFormType.Eyebrow, color = FitFormColors.Faint)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NO SESSIONS YET", style = FitFormType.DisplayMd, color = FitFormColors.Bone)
        Spacer(Modifier.height(12.dp))
        Text(
            "Start a Gym Coach or Shot Coach session to begin building your archive.",
            style = FitFormType.Body,
            color = FitFormColors.Mute,
        )
    }
}
