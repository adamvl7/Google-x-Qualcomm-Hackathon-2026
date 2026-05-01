package com.fitform.app.feedback

import com.fitform.app.analysis.AngleExtractor
import com.fitform.app.analysis.FormClassifier
import com.fitform.app.analysis.shot.ShotAnalyzer
import com.fitform.app.analysis.squat.SquatAnalyzer
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.PoseResult
import kotlin.math.roundToInt

/**
 * Routes a [PoseResult] to the right analyzer for the current mode, then
 * optionally blends the rule-based score with the [FormClassifier] output.
 *
 * When the classifier is present (model asset loaded):
 *   finalScore = ruleScore * 0.70 + classifierScore * 0.30
 *
 * When absent, the app falls back to 100% rule-based scoring so it works
 * before training data is collected and the model is deployed.
 */
class FeedbackEngine(
    initialMode: ExerciseMode,
    private var classifier: FormClassifier? = null,
) {
    private val squat = SquatAnalyzer()
    private val shot  = ShotAnalyzer()

    @Volatile var mode: ExerciseMode = initialMode

    fun setClassifier(fc: FormClassifier?) {
        classifier = fc
    }

    fun analyze(pose: PoseResult): AnalysisResult {
        val base = when (mode) {
            ExerciseMode.GYM  -> squat.analyze(pose)
            ExerciseMode.SHOT -> shot.analyze(pose)
        }

        val fc = classifier
        if (fc == null || !base.tracking) return base

        val features  = AngleExtractor.extract(pose, mode)
        val mlScore   = fc.classify(features) * 100f
        val blended   = (base.score * RULE_WEIGHT + mlScore * ML_WEIGHT).roundToInt().coerceIn(0, 100)

        return base.copy(score = blended)
    }

    companion object {
        private const val RULE_WEIGHT = 0.70f
        private const val ML_WEIGHT   = 0.30f
    }
}
