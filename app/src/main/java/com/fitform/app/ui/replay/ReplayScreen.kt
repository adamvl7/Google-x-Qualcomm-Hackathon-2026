package com.fitform.app.ui.replay

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fitform.app.FitFormApp
import com.fitform.app.model.RepData
import com.fitform.app.model.SessionSummary
import com.fitform.app.model.Severity
import com.fitform.app.model.SetRecap
import com.fitform.app.recap.SetRecapGenerator
import com.fitform.app.replay.ReplayEngine
import com.fitform.app.replay.ReplayFrame
import com.fitform.app.ui.components.CueBanner
import com.fitform.app.ui.components.PoseOverlay
import com.fitform.app.ui.components.SectionEyebrow
import com.fitform.app.ui.components.TickerRule
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ReplayScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FitFormApp

    var summary by remember(sessionId) { mutableStateOf<SessionSummary?>(null) }
    var recapGenerating by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        summary = withContext(Dispatchers.IO) { app.sessionRepository.load(sessionId) }
    }

    LaunchedEffect(summary?.sessionId, summary?.recap) {
        val loaded = summary
        if (loaded != null && loaded.recap == null && !recapGenerating) {
            recapGenerating = true
            summary = withContext(Dispatchers.IO) {
                SetRecapGenerator(app, app.sessionRepository).generateAndSave(loaded.sessionId)
            } ?: loaded
            recapGenerating = false
        }
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
            delay(33L)
        }
    }

    val frame: ReplayFrame = engine?.frameAt(positionMs) ?: ReplayFrame.EMPTY
    val showCue = frame.cue.isNotEmpty() && frame.severity != Severity.GREEN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink),
    ) {
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
                Text("<-", style = FitFormType.DisplayMd, color = FitFormColors.Bone)
                Text("BACK", style = FitFormType.LabelLg, color = FitFormColors.Mute)
            }
            Text("REPLAY", style = FitFormType.LabelLg, color = FitFormColors.Acid)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(FitFormColors.Surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(REPLAY_VIDEO_ASPECT_RATIO, matchHeightConstraintsFirst = true)
                    .align(Alignment.Center),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            useController = true
                            this.player = player
                        }
                    },
                    update = {
                        it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        it.player = player
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                PoseOverlay(
                    pose = frame.pose,
                    severity = frame.severity,
                    modifier = Modifier.fillMaxSize(),
                    mirror = false,
                )
            }
        }

        if (showCue) {
            CueBanner(
                cue = frame.cue,
                severity = frame.severity,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val s = summary ?: return
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = if (showCue) 8.dp else 16.dp, bottom = 8.dp),
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
                Text(
                    text = if (s.mode == "shot") "${s.repCount} SHOTS" else "${s.repCount} REPS",
                    style = FitFormType.Eyebrow,
                    color = FitFormColors.Mute,
                )
            }
            TickerRule()
            SectionEyebrow("COACH RECAP")
            RecapPanel(recap = s.recap, generating = recapGenerating)
            TickerRule()
            SectionEyebrow(if (s.mode == "shot") "SHOT TIMELINE" else "REP TIMELINE")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.reps, key = { it.repNumber }) { rep ->
                    TimelineRepRow(
                        rep = rep,
                        label = if (s.mode == "shot") "SHOT" else "REP",
                        onSeek = { player.seekTo(rep.startTimestampMs) },
                    )
                }
            }
        }
    }
}

private const val REPLAY_VIDEO_ASPECT_RATIO = 9f / 16f

@Composable
private fun TimelineRepRow(rep: RepData, label: String, onSeek: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .clickable { onSeek() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$label ${rep.repNumber.toString().padStart(2, '0')}", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
        Text(rep.topCue, style = FitFormType.Body, color = FitFormColors.Bone, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RecapPanel(recap: SetRecap?, generating: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FitFormColors.Surface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (recap == null) {
            Text(
                if (generating) "Generating summary..." else "Summary will appear here after analysis.",
                style = FitFormType.Body,
                color = FitFormColors.Bone,
            )
            return@Column
        }

        Text(recap.overall, style = FitFormType.Body, color = FitFormColors.Bone)
        RecapList(title = "WHAT WENT WELL", items = recap.wentWell)
        RecapList(title = "WHAT TO IMPROVE", items = recap.needsWork)
        RecapList(title = "NEXT SET FOCUS", items = recap.tips)
        Text(
            text = "${recap.modelName.uppercase()} | ${recap.status.uppercase()}",
            style = FitFormType.Eyebrow,
            color = FitFormColors.Mute,
        )
    }
}

@Composable
private fun RecapList(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = FitFormType.Eyebrow, color = FitFormColors.Acid)
        items.forEach { item ->
            Text("- $item", style = FitFormType.Caption, color = FitFormColors.Bone)
        }
    }
}
