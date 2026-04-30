package com.fitform.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.FrameData
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.RepData
import com.fitform.app.model.SessionEvent
import com.fitform.app.model.SessionSummary
import com.fitform.app.storage.SessionDir
import com.fitform.app.storage.SessionStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Coordinates the recording lifecycle for a single set:
 *
 *   start()      → begins MP4 capture + clears in-memory buffers
 *   onFrame()    → appends keypoints + cue events
 *   markRepStart / markRepEnd
 *   end()        → stops video, writes analysis.json, returns summary
 *
 * The set timer (timestamps relative to start) drives both the saved
 * frame timeline and the rep ranges, so replay can sync skeleton with
 * video position.
 *
 * Auto-rep detection is intentionally out of scope for the MVP — we
 * use manual rep boundaries triggered from the UI. // TODO: future work.
 */
class SessionRecorder(
    private val context: Context,
    private val storage: SessionStorage,
    private val videoCapture: VideoCapture<Recorder>?,
    private val mode: ExerciseMode,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var activeRecording: Recording? = null
    private var session: SessionDir? = null
    private var startedAtElapsedMs: Long = 0L
    private var startedAtWallClock: Date = Date()

    private val frames = mutableListOf<FrameData>()
    private val events = mutableListOf<SessionEvent>()
    private val reps = mutableListOf<RepData>()

    private var currentRep: PendingRep? = null
    private var bestForCurrentRep: AnalysisResult? = null
    private var allowVideo: Boolean = true

    val isActive: Boolean get() = session != null
    val repCountFinalized: Int get() = reps.size

    @SuppressLint("MissingPermission")
    fun start(): SessionDir {
        val s = storage.newSession()
        session = s
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        startedAtWallClock = Date()
        frames.clear(); events.clear(); reps.clear()
        currentRep = null

        val capture = videoCapture
        if (capture != null && allowVideo) {
            val options = FileOutputOptions.Builder(storage.videoFile(s)).build()
            val pending = capture.output.prepareRecording(context, options)
            // Audio is optional — we record video only to avoid extra perms.
            activeRecording = try {
                pending.start(ContextCompat.getMainExecutor(context)) { event: VideoRecordEvent ->
                    if (event is VideoRecordEvent.Finalize && event.hasError()) {
                        android.util.Log.w("SessionRecorder", "Video finalize error: ${event.error}")
                    }
                }
            } catch (_: Throwable) { null }
        }
        return s
    }

    fun onFrame(result: AnalysisResult) {
        val s = session ?: return
        val ts = result.timestampMs.toRelativeMs()
        // Down-sample frame data to ~10 fps to keep JSON manageable.
        val last = frames.lastOrNull()?.timestampMs ?: -1L
        if (ts - last >= 100L) {
            frames.add(
                FrameData(
                    timestampMs = ts,
                    keypoints = buildMap {
                        for (i in 0 until KeypointIndex.COUNT) {
                            val kp = result.pose[i]
                            if (kp.confidence >= 0.3f) put(KeypointIndex.NAMES[i], kp)
                        }
                    },
                )
            )
        }

        val rep = currentRep
        if (rep != null && result.tracking) {
            val best = bestForCurrentRep
            if (best == null || result.score > best.score) bestForCurrentRep = result
        }

        // Record only meaningful (non-green) cue transitions to avoid spam.
        val lastEvent = events.lastOrNull()
        if (result.severity != com.fitform.app.model.Severity.GREEN &&
            (lastEvent == null || lastEvent.cue != result.cue || (ts - lastEvent.timestampMs) > 1500L)
        ) {
            events += SessionEvent(
                timestampMs = ts,
                rep = rep?.repNumber ?: 0,
                cue = result.cue,
                severity = result.severity,
            )
        }
    }

    fun startRep() {
        val s = session ?: return
        if (currentRep != null) return
        val ts = SystemClock.elapsedRealtime() - startedAtElapsedMs
        currentRep = PendingRep(repNumber = reps.size + 1, startMs = ts)
        bestForCurrentRep = null
    }

    /** Closes the in-progress rep and returns its computed [RepData]. */
    fun endRep(latestResult: AnalysisResult?): RepData? {
        val rep = currentRep ?: return null
        val ts = SystemClock.elapsedRealtime() - startedAtElapsedMs
        val best = bestForCurrentRep ?: latestResult
        val data = RepData(
            repNumber = rep.repNumber,
            startTimestampMs = rep.startMs,
            endTimestampMs = ts,
            score = best?.score ?: 0,
            topCue = best?.cue ?: "Tracking",
        )
        reps.add(data)
        currentRep = null
        bestForCurrentRep = null
        return data
    }

    fun end(): SessionSummary? {
        val s = session ?: return null
        // Close any unfinished rep with a default score.
        if (currentRep != null) endRep(null)

        try { activeRecording?.stop() } catch (_: Throwable) {}
        activeRecording = null

        val avg = if (reps.isEmpty()) 0 else reps.map { it.score }.average().toInt()
        val summary = SessionSummary(
            sessionId = s.id,
            mode = mode.routeKey,
            exercise = if (mode == ExerciseMode.GYM) "squat" else "jumpshot",
            createdAt = ISO.format(startedAtWallClock),
            repCount = reps.size,
            averageScore = avg,
            reps = reps.toList(),
            frameData = frames.toList(),
            events = events.toList(),
        )
        storage.saveAnalysis(s, summary)
        session = null
        return summary
    }

    fun cancel() {
        try { activeRecording?.stop() } catch (_: Throwable) {}
        activeRecording = null
        session?.let { storage.delete(it.id) }
        session = null
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun Long.toRelativeMs(): Long = this - startedAtElapsedMsWallReference()

    /**
     * The pose timestamps come from the camera frame clock, which on
     * CameraX is also [SystemClock.elapsedRealtime] in milliseconds.
     */
    private fun startedAtElapsedMsWallReference(): Long = startedAtElapsedMs

    private data class PendingRep(val repNumber: Int, val startMs: Long)

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
