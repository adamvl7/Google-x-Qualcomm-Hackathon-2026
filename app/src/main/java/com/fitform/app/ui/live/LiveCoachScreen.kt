package com.fitform.app.ui.live

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fitform.app.FitFormApp
import com.fitform.app.camera.CameraPreviewSurface
import com.fitform.app.model.ExerciseMode
import com.fitform.app.ui.components.CueBanner
import com.fitform.app.ui.components.LiveActionButton
import com.fitform.app.ui.components.OnDeviceBadge
import com.fitform.app.ui.components.PoseOverlay
import com.fitform.app.ui.components.ScoreBadge
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormType
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LiveCoachScreen(
    mode: ExerciseMode,
    onExit: () -> Unit,
    onSetComplete: (sessionId: String) -> Unit,
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    if (!cameraPermission.status.isGranted) {
        PermissionGate(onExit = onExit, onRequest = { cameraPermission.launchPermissionRequest() })
        return
    }

    val app = context.applicationContext as FitFormApp
    val viewModel: LiveCoachViewModel = viewModel(
        key = "live-${mode.routeKey}",
        factory = viewModelFactory {
            initializer { LiveCoachViewModel(app = app, mode = mode) }
        },
    )

    // providerFuture().get() is blocking — must run off the main thread.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // Camera preview
        CameraPreviewSurface(
            cameraManager = viewModel.cameraManager,
            modifier = Modifier.fillMaxSize(),
        )

        // Skeleton overlay
        PoseOverlay(
            pose = state.pose,
            severity = state.severity,
            modifier = Modifier.fillMaxSize(),
            mirror = false,
        )

        // Top scrim
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(FitFormColors.ScrimTop)
                .align(Alignment.TopCenter)
        )
        // Bottom scrim
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(FitFormColors.ScrimBottom)
                .align(Alignment.BottomCenter)
        )

        // Top HUD: mode chip + on-device badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.mode.displayLabel.uppercase(), style = FitFormType.LabelLg, color = FitFormColors.Bone)
                Text(state.mode.exerciseLabel.uppercase() + " · SIDE VIEW", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
            }
            ModelChip(state)
        }

        // Score on top-right OR centered when paused — keep top-right.
        ScoreBadge(
            score = state.score,
            paused = !state.tracking,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 20.dp),
        )

        // Cue banner — anchor below score, full width
        CueBanner(
            cue = state.cue,
            severity = state.severity,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )

        // Bottom HUD: rep counter + set controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RepReadout(state = state)
            SetControls(
                state = state,
                onStartSet = viewModel::startSet,
                onEndSet = {
                    val id = viewModel.endSet()
                    if (id != null) onSetComplete(id) else onExit()
                },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun ModelChip(state: LiveCoachUiState) {
    val ms = if (state.inferenceMs > 0) "${state.inferenceMs}ms · " else ""
    val (label, color) = when (state.modelKind) {
        ModelKind.LiteRtNpu -> "${ms}NPU" to FitFormColors.Acid
        ModelKind.LiteRtCpu -> "${ms}CPU" to FitFormColors.StatusAmber
        ModelKind.Mock       -> "MOCK"    to FitFormColors.Mute
    }
    Row(
        modifier = Modifier
            .background(FitFormColors.Surface.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(color))
        Text(label, style = FitFormType.Eyebrow, color = color)
    }
}

@Composable
private fun RepReadout(state: LiveCoachUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("REPS", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
            Text(
                text = state.repCount.toString().padStart(2, '0'),
                style = FitFormType.DisplayHero,
                color = FitFormColors.Bone,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("LAST REP", style = FitFormType.Eyebrow, color = FitFormColors.Mute)
            val score = state.lastRepScore?.let { "$it%" } ?: "—"
            Text(score, style = FitFormType.DisplayMd, color = FitFormColors.Bone)
            val cue = state.lastRepCue ?: (if (state.setActive) "Reps counted automatically" else "Tap Start Set to begin")
            Text(cue, style = FitFormType.Caption, color = FitFormColors.Mute)
        }
    }
}

@Composable
private fun SetControls(
    state: LiveCoachUiState,
    onStartSet: () -> Unit,
    onEndSet: () -> Unit,
    onExit: () -> Unit,
) {
    if (!state.setActive) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveActionButton(
                label = "Exit",
                onClick = onExit,
                accent = FitFormColors.Bone,
            )
            LiveActionButton(
                label = "Start Set",
                onClick = onStartSet,
                accent = FitFormColors.Acid,
                filled = true,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        // Reps are counted automatically — user just ends the set when done.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveActionButton(
                label = "End Set",
                onClick = onEndSet,
                accent = FitFormColors.Bone,
            )
            // Visual indicator while a rep is actively being tracked.
            if (state.repInProgress) {
                LiveActionButton(
                    label = "● REP",
                    onClick = {},
                    accent = FitFormColors.StatusAmber,
                    filled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(onExit: () -> Unit, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FitFormColors.Ink)
            .padding(horizontal = 24.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        OnDeviceBadge()
        Spacer(Modifier.height(20.dp))
        Text("CAMERA REQUIRED", style = FitFormType.Eyebrow, color = FitFormColors.Acid)
        Text("FitForm needs your camera to coach in real time. Frames never leave the device.",
            style = FitFormType.BodyLg, color = FitFormColors.Bone)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveActionButton(label = "Back", onClick = onExit, accent = FitFormColors.Bone)
            LiveActionButton(label = "Grant", onClick = onRequest, accent = FitFormColors.Acid, filled = true, modifier = Modifier.weight(1f))
        }
    }
}
