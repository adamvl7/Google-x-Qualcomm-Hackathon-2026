package com.fitform.app.feedback

import com.fitform.app.analysis.shot.ShotAnalyzer
import com.fitform.app.analysis.squat.SquatAnalyzer
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.PoseResult

/**
 * Routes a [PoseResult] to the right analyzer for the current mode.
 * Holds analyzer instances so per-frame allocation stays low.
 */
class FeedbackEngine(initialMode: ExerciseMode) {
    private val squat = SquatAnalyzer()
    private val shot = ShotAnalyzer()

    @Volatile var mode: ExerciseMode = initialMode

    fun analyze(pose: PoseResult): AnalysisResult = when (mode) {
        ExerciseMode.GYM -> squat.analyze(pose)
        ExerciseMode.SHOT -> shot.analyze(pose)
    }
}
