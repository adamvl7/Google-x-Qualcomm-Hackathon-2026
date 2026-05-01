package com.fitform.app.ui.replay

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fitform.app.FitFormApp
import com.fitform.app.model.RepData
import com.fitform.app.model.SessionSummary
import com.fitform.app.model.Severity
import com.fitform.app.replay.ReplayEngine
import com.fitform.app.replay.ReplayFrame
import com.fitform.app.ui.components.CueBanner
import com.fitform.app.ui.components.PoseOverlay
import com.fitform.app.ui.components.ScoreBadge
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.components.scoreColor
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType
import kotlinx.coroutines.delay

@Composable
fun ReplayScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FitFormApp
    val summary by produceState<SessionSummary?>(initialValue = null, sessionId) {
        value = app.sessionRepository.load(sessionId)
    }

    val engine = remember(summary) { summary?.let { ReplayEngine(it) } }
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player, sessionId) {
        val videoFile = app.sessionRepository.videoFile(sessionId)
        if (videoFile.exists()) {
            player.setMediaItem(MediaItem.fromUri(videoFile.toURI().toString()))
            player.prepare()
            player.playWhenReady = true
        }
        onDispose { player.release() }
    }

    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(33L) // ~30 fps overlay refresh
        }
    }

    // Per-frame data from stored analysis.json — exact stats from the live session.
    val frame: ReplayFrame = engine?.frameAt(positionMs) ?: ReplayFrame.EMPTY
    val showStats = frame.score > 0 || frame.cue.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink),
    ) {
        // top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
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
            Text("REPLAY", style = FitFormType.LabelLg, color = FitFormColors.Acid)
        }

        // ── Video + live overlay ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(FitFormColors.Surface)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )

            // Skeleton overlay using stored keypoints — same data that was shown live
            PoseOverlay(
                pose = frame.pose,
                severity = frame.severity,
                modifier = Modifier.fillMaxSize(),
                mirror = false,
            )

            // Score badge — updates frame-by-frame with stored score
            if (showStats) {
                ScoreBadge(
                    score = frame.score,
                    paused = false,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }

        // ── Cue banner — updates frame-by-frame with stored cue ───────────────
        if (showStats && frame.cue.isNotEmpty()) {
            CueBanner(
                cue = frame.cue,
                severity = frame.severity,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val s = summary ?: return
        // ── Session summary band ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = if (showStats && frame.cue.isNotEmpty()) 8.dp else 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = if (s.mode == "gym") "SQUAT" else "JUMP SHOT",
                        style = FitFormType.DisplayMd,
                        color = FitFormColors.Bone,
                    )
                    Text(s.createdAt, style = FitFormType.Caption, color = FitFormColors.Mute)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.averageScore}% AVG", style = FitFormType.LabelLg, color = scoreColor(s.averageScore))
                    Text("${s.repCount} REPS", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
                }
            }
            TickerRule()
            SectionEyebrow("REP TIMELINE")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.reps, key = { it.repNumber }) { rep ->
                    TimelineRepRow(rep = rep, onSeek = { player.seekTo(rep.startTimestampMs) })
                }
            }
        }
    }
}

@Composable
private fun TimelineRepRow(rep: RepData, onSeek: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .clickable { onSeek() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("REP ${rep.repNumber.toString().padStart(2, '0')}", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
        Text(rep.topCue, style = FitFormType.Body, color = FitFormColors.Bone, modifier = Modifier.weight(1f))
        Text("${rep.score}%", style = FitFormType.LabelLg, color = scoreColor(rep.score))
    }
}

private fun formatMs(ms: Long): String {
    val seconds = ms / 1000
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
