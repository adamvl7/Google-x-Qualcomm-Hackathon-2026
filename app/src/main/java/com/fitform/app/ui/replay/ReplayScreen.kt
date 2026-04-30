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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fitform.app.FitFormApp
import com.fitform.app.model.PoseResult
import com.fitform.app.model.RepData
import com.fitform.app.model.SessionEvent
import com.fitform.app.model.SessionSummary
import com.fitform.app.model.Severity
import com.fitform.app.replay.ReplayEngine
import com.fitform.app.ui.components.PoseOverlay
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

    val pose: PoseResult = engine?.poseAt(positionMs) ?: PoseResult.EMPTY
    val activeEvent: SessionEvent? = engine?.activeEvent(positionMs)

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

        // video + overlay
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
            PoseOverlay(
                pose = pose,
                severity = activeEvent?.severity ?: Severity.GREEN,
                modifier = Modifier.fillMaxSize(),
                mirror = false,
            )
            // active cue chip
            if (activeEvent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(FitFormColors.Surface.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(when (activeEvent.severity) {
                                    Severity.GREEN -> FitFormColors.StatusGreen
                                    Severity.YELLOW -> FitFormColors.StatusAmber
                                    Severity.RED -> FitFormColors.StatusRed
                                })
                        )
                        Text(activeEvent.cue.uppercase(), style = FitFormType.Eyebrow, color = FitFormColors.Bone)
                    }
                }
            }
        }

        val s = summary ?: return
        // summary band
        Column(modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(if (s.mode == "gym") "SQUAT" else "JUMP SHOT", style = FitFormType.DisplayMd, color = FitFormColors.Bone)
                    Text(s.createdAt, style = FitFormType.Caption, color = FitFormColors.Mute)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.averageScore}%", style = FitFormType.DisplayMd, color = scoreColor(s.averageScore))
                    Text("${s.repCount} REPS", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
                }
            }
            TickerRule()
            SectionEyebrow("REP TIMELINE")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.reps, key = { it.repNumber }) { rep ->
                    TimelineRepRow(rep = rep, onSeek = { player.seekTo(rep.startTimestampMs) })
                }
                items(s.events) { event ->
                    EventRow(event)
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

@Composable
private fun EventRow(event: SessionEvent) {
    val color = when (event.severity) {
        Severity.GREEN -> FitFormColors.StatusGreen
        Severity.YELLOW -> FitFormColors.StatusAmber
        Severity.RED -> FitFormColors.StatusRed
    }
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(width = 6.dp, height = 18.dp)
                .background(color)
        )
        Text(formatMs(event.timestampMs), style = FitFormType.Stat, color = FitFormColors.Faint)
        Text(event.cue, style = FitFormType.Caption, color = FitFormColors.Mute)
    }
}

private fun formatMs(ms: Long): String {
    val seconds = ms / 1000
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
