package com.fitform.app.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wraps CameraX setup. Binds Preview + ImageAnalysis + VideoCapture
 * concurrently so the same frame source feeds the live skeleton overlay,
 * pose inference, and the saved MP4.
 *
 * If the device cannot bind all three (it should on the S25 Ultra),
 * we fall back to Preview + ImageAnalysis only — recording disabled.
 */
class CameraManager(private val context: Context) {

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val preview: Preview = Preview.Builder().build()

    private val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    private val recorder: Recorder = Recorder.Builder()
        .setQualitySelector(androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.HD))
        .build()

    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    fun setAnalyzer(analyzer: (ImageProxy) -> Unit) {
        imageAnalysis.setAnalyzer(analysisExecutor) { proxy -> analyzer(proxy) }
    }

    fun providerFuture(): ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(context)

    /**
     * Bind the use cases. Returns true if all three (preview + analysis +
     * video) were bound; false if we had to fall back to preview + analysis.
     */
    fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider): Boolean {
        provider.unbindAll()
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        return try {
            provider.bindToLifecycle(owner, selector, preview, imageAnalysis, videoCapture)
            android.util.Log.i("FitForm/Camera", "Bound Preview + ImageAnalysis + VideoCapture")
            true
        } catch (t: Throwable) {
            android.util.Log.w("FitForm/Camera", "Full bind failed, retrying without VideoCapture", t)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, imageAnalysis)
                android.util.Log.i("FitForm/Camera", "Bound Preview + ImageAnalysis (no video)")
            } catch (t2: Throwable) {
                android.util.Log.e("FitForm/Camera", "Camera bind failed entirely", t2)
            }
            false
        }
    }

    fun shutdown() {
        analysisExecutor.shutdown()
    }
}
