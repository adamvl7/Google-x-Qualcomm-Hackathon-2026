package com.fitform.app.ui.live

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.fitform.app.ui.components.LiveActionButton
import com.fitform.app.ui.components.OnDeviceBadge
import com.fitform.app.ui.components.PoseOverlay
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            cameraManager = viewModel.cameraManager,
            modifier = Modifier.fillMaxSize(),
        )

        PoseOverlay(
            pose = state.pose,
            severity = state.severity,
            modifier = Modifier.fillMaxSize(),
            mirror = false,
        )

        ModelStatus(
            state = state,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(FitFormColors.ScrimBottom)
                .align(Alignment.BottomCenter)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.setActive) {
                CountReadout(state = state)
            }
            SetControls(
                state = state,
                onStartSet = viewModel::startSet,
                onEndSet = {
                    val id = viewModel.endSet()
                    if (id != null) onSetComplete(id) else onExit()
                },
            )
        }
    }
}

@Composable
private fun ModelStatus(state: LiveCoachUiState, modifier: Modifier = Modifier) {
    val label = when (state.modelKind) {
        ModelKind.LiteRtNpu -> "MoveNet NPU"
        ModelKind.LiteRtCpu -> "MoveNet CPU"
        ModelKind.Mock -> "Mock overlay"
    }
    val color = when (state.modelKind) {
        ModelKind.LiteRtNpu -> FitFormColors.Acid
        ModelKind.LiteRtCpu -> FitFormColors.StatusAmber
        ModelKind.Mock -> FitFormColors.StatusRed
    }
    Row(
        modifier = modifier
            .background(FitFormColors.Surface.copy(alpha = 0.82f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color),
        )
        Text(label.uppercase(), style = FitFormType.Eyebrow, color = color)
    }
}

@Composable
private fun CountReadout(state: LiveCoachUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (state.mode == ExerciseMode.SHOT) "SHOTS" else "REPS",
            style = FitFormType.Eyebrow,
            color = FitFormColors.Mute,
        )
        Text(
            text = state.repCount.toString().padStart(2, '0'),
            style = FitFormType.DisplayHero,
            color = FitFormColors.Bone,
        )
    }
}

@Composable
private fun SetControls(
    state: LiveCoachUiState,
    onStartSet: () -> Unit,
    onEndSet: () -> Unit,
) {
    LiveActionButton(
        label = if (state.setActive) "Finish Set" else "Start Set",
        onClick = if (state.setActive) onEndSet else onStartSet,
        accent = if (state.setActive) FitFormColors.Bone else FitFormColors.Acid,
        filled = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
        Text(
            "FitForm needs your camera to record the set. Frames stay on device.",
            style = FitFormType.BodyLg,
            color = FitFormColors.Bone,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveActionButton(label = "Back", onClick = onExit, accent = FitFormColors.Bone)
            LiveActionButton(
                label = "Grant",
                onClick = onRequest,
                accent = FitFormColors.Acid,
                filled = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
