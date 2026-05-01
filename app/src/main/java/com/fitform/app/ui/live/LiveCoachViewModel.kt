package com.fitform.app.ui.live

import android.app.Application
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitform.app.FitFormApp
import com.fitform.app.analysis.FormClassifier
import com.fitform.app.analysis.RepDetector
import com.fitform.app.camera.CameraManager
import com.fitform.app.feedback.FeedbackEngine
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Severity
import com.fitform.app.pose.LiteRtPoseEstimator
import com.fitform.app.pose.MockPoseEstimator
import com.fitform.app.pose.PoseEstimator
import com.fitform.app.recording.SessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orchestrates the live coaching screen:
 *
 *   ImageAnalysis frame → PoseEstimator → FeedbackEngine → RepDetector → UiState
 *                                          ↓ (when set is in progress)
 *                                       SessionRecorder
 *
 * FeedbackEngine blends rule-based geometry scores with the optional
 * FormClassifier (second LiteRT model on NPU) for calibrated form scoring.
 * Rep boundaries are detected automatically by [RepDetector].
 *
 * Shot mode uses a faster EMA and shorter cue debounce than squat mode because
 * a jump shot lasts ~0.7 s and needs immediate feedback before it's over.
 */
class LiveCoachViewModel(
    private val app: Application,
    val mode: ExerciseMode,
) : AndroidViewModel(app) {

    val cameraManager = CameraManager(app)

    @Volatile private var poseEstimator: PoseEstimator? = null

    private val feedback = FeedbackEngine(mode)

    private val recorder = SessionRecorder(
        context = app,
        storage = (app as FitFormApp).sessionRepository.storage,
        videoCapture = cameraManager.videoCapture,
        mode = mode,
    )

    private val repDetector = RepDetector(
        mode = mode,
        onRepStarted = {
            recorder.startRep()
            _uiState.update { it.copy(repInProgress = true) }
        },
        onRepCompleted = { latestResult ->
            val rep = recorder.endRep(latestResult)
            if (rep != null) {
                _uiState.update {
                    it.copy(
                        repInProgress = false,
                        repCount = rep.repNumber,
                        lastRepScore = rep.score,
                        lastRepCue = rep.topCue,
                    )
                }
            }
        },
    )

    private val _uiState = MutableStateFlow(
        LiveCoachUiState(mode = mode, modelKind = ModelKind.Mock)
    )
    val uiState: StateFlow<LiveCoachUiState> = _uiState.asStateFlow()

    private val analysisMutex = Mutex()
    private var lastResult: AnalysisResult? = null

    // ── Score smoothing (EMA) ─────────────────────────────────────────────────
    // Shot mode uses a higher alpha (0.28) so the score updates fast enough to
    // be meaningful during a ~0.7 s jump shot. Squat mode uses 0.15 for
    // steadier display during a slower ~2-3 s movement.
    private val scoreEmaAlpha  = if (mode == ExerciseMode.SHOT) 0.28f else 0.15f
    private val scoreEmaRetain = 1f - scoreEmaAlpha
    private var smoothedScore  = 100f

    // ── Cue debounce ─────────────────────────────────────────────────────────
    // Shot: shorter holds so form cues appear within the shot window.
    // Squat: standard holds for stability during slower reps.
    private var pendingCue = ""
    private var pendingSeverity = Severity.GREEN
    private var pendingCueFirstSeenMs = 0L
    private var displayedCue = "Get into frame"
    private var displayedSeverity = Severity.YELLOW

    init {
        cameraManager.setAnalyzer { proxy -> processFrame(proxy) }

        viewModelScope.launch {
            val (estimator, classifier) = withContext(Dispatchers.IO) {
                val est = LiteRtPoseEstimator.tryCreate(app) ?: MockPoseEstimator(mode)
                val cls = FormClassifier.tryCreate(app, mode)
                est to cls
            }
            poseEstimator = estimator
            feedback.setClassifier(classifier)

            val kind = when {
                estimator is LiteRtPoseEstimator && estimator.usingNnApi -> ModelKind.LiteRtNpu
                estimator is LiteRtPoseEstimator -> ModelKind.LiteRtCpu
                else -> ModelKind.Mock
            }
            _uiState.update { it.copy(modelKind = kind) }
        }
    }

    private fun processFrame(proxy: ImageProxy) {
        viewModelScope.launch {
            analysisMutex.withLock {
                val estimator = poseEstimator
                if (estimator == null) {
                    proxy.close()
                    return@withLock
                }
                val pose: PoseResult = estimator.estimatePose(proxy) ?: return@withLock
                val result = feedback.analyze(pose)
                lastResult = result

                if (recorder.isActive) {
                    recorder.onFrame(result)
                    repDetector.onFrame(result)
                }

                // ── Score smoothing ───────────────────────────────────────────
                if (result.tracking) {
                    smoothedScore = smoothedScore * scoreEmaRetain + result.score * scoreEmaAlpha
                }
                val stableScore = smoothedScore.toInt().coerceIn(0, 100)

                // ── Cue debounce ──────────────────────────────────────────────
                val now = System.currentTimeMillis()
                if (result.cue != pendingCue) {
                    pendingCue = result.cue
                    pendingSeverity = result.severity
                    pendingCueFirstSeenMs = now
                }
                if (now - pendingCueFirstSeenMs >= pendingSeverity.holdMs()) {
                    displayedCue = pendingCue
                    displayedSeverity = pendingSeverity
                }

                val inferenceMs = (poseEstimator as? LiteRtPoseEstimator)?.lastInferenceMs?.toInt() ?: 0
                _uiState.update { state ->
                    state.copy(
                        pose = result.pose,
                        score = stableScore,
                        cue = displayedCue,
                        severity = displayedSeverity,
                        tracking = result.tracking,
                        inferenceMs = inferenceMs,
                    )
                }
            }
        }
    }

    fun startSet() {
        recorder.start()
        repDetector.reset()
        repDetector.active = true
        _uiState.update { it.copy(setActive = true, repCount = 0, repInProgress = false, lastRepScore = null, lastRepCue = null) }
    }

    fun endSet(): String? {
        repDetector.active = false
        if (recorder.isActive && _uiState.value.repInProgress) {
            recorder.endRep(lastResult)
        }
        val summary = recorder.end() ?: return null
        _uiState.update { it.copy(setActive = false, repInProgress = false) }
        return summary.sessionId
    }

    override fun onCleared() {
        super.onCleared()
        repDetector.active = false
        try { recorder.cancel() } catch (_: Throwable) {}
        try { recorder.shutdown() } catch (_: Throwable) {}
        try { poseEstimator?.close() } catch (_: Throwable) {}
        cameraManager.shutdown()
    }

    // Per-severity + per-mode cue debounce hold times.
    private fun Severity.holdMs(): Long = when (this) {
        Severity.GREEN  -> 0L
        Severity.YELLOW -> if (mode == ExerciseMode.SHOT) 180L else 450L
        Severity.RED    -> if (mode == ExerciseMode.SHOT) 300L else 600L
    }
}

enum class ModelKind { LiteRtNpu, LiteRtCpu, Mock }

data class LiveCoachUiState(
    val mode: ExerciseMode,
    val pose: PoseResult = PoseResult.EMPTY,
    val score: Int = 100,
    val cue: String = "Get into frame",
    val severity: Severity = Severity.YELLOW,
    val tracking: Boolean = true,
    val setActive: Boolean = false,
    val repInProgress: Boolean = false,
    val repCount: Int = 0,
    val lastRepScore: Int? = null,
    val lastRepCue: String? = null,
    val modelKind: ModelKind = ModelKind.Mock,
    val inferenceMs: Int = 0,
)
