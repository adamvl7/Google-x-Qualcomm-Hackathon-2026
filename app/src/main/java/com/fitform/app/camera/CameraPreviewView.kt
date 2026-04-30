package com.fitform.app.camera

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.observe

@Composable
fun CameraPreviewSurface(
    cameraManager: CameraManager,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE

                cameraManager.preview.setSurfaceProvider(surfaceProvider)
                previewStreamState.observe(lifecycleOwner) { state ->
                    android.util.Log.i("FitForm/Camera", "Preview stream state=$state")
                }

                // Bind after AndroidView is attached so PreviewView can provide a real surface.
                post {
                    val future = cameraManager.providerFuture()
                    future.addListener({
                        runCatching {
                            cameraManager.bind(lifecycleOwner, future.get())
                        }.onFailure { t ->
                            android.util.Log.e("FitForm/Camera", "Camera bind failed in post{}", t)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        },
        update = { _ -> },
    )
}
