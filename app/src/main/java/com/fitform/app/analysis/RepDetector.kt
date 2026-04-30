package com.fitform.app.analysis

import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.ExerciseMode

/**
 * Automatic rep detection from pose analysis results.
 *
 * Watches key joint angles across frames to detect the start and end of
 * each rep without requiring manual button presses.
 *
 * Squat rep cycle (knee angle at the knee joint):
 *   STANDING (angle > 155°)
 *     → DESCENDING (angle drops below 145°)  ← rep starts
 *     → AT_BOTTOM  (angle drops below 115°)  ← must reach depth
 *     → ASCENDING  (angle rises above 125°)
 *     → STANDING   (angle rises above 155°)  ← rep complete
 *
 * Jump-shot rep cycle (wrist height relative to shoulder):
 *   LOADING   (wrist below shoulder)
 *     → SHOOTING  (wrist rises above shoulder) ← rep starts
 *     → LOADING   (wrist drops back below)     ← rep complete
 *
 * Hysteresis bands prevent noise from triggering false reps.
 */
class RepDetector(
    private val mode: ExerciseMode,
    private val onRepStarted: () -> Unit,
    private val onRepCompleted: (latestResult: AnalysisResult) -> Unit,
) {
    var active: Boolean = false

    private enum class SquatPhase { STANDING, DESCENDING, AT_BOTTOM, ASCENDING }
    private enum class ShotPhase  { LOADING, SHOOTING }

    private var squatPhase = SquatPhase.STANDING
    private var shotPhase  = ShotPhase.LOADING

    fun reset() {
        squatPhase = SquatPhase.STANDING
        shotPhase  = ShotPhase.LOADING
    }

    fun onFrame(result: AnalysisResult) {
        if (!active || !result.tracking) return
        when (mode) {
            ExerciseMode.GYM  -> processSquat(result)
            ExerciseMode.SHOT -> processShot(result)
        }
    }

    private fun processSquat(result: AnalysisResult) {
        val pose = result.pose
        val side = GeometryUtils.selectBetterSide(pose)
        val ids  = GeometryUtils.indicesFor(side)
        val hip   = pose[ids.hip]
        val knee  = pose[ids.knee]
        val ankle = pose[ids.ankle]

        if (hip.confidence < 0.25f || knee.confidence < 0.25f || ankle.confidence < 0.25f) return

        val kneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)

        when (squatPhase) {
            SquatPhase.STANDING -> {
                if (kneeAngle < SQUAT_DESCEND_START) {
                    squatPhase = SquatPhase.DESCENDING
                    onRepStarted()
                }
            }
            SquatPhase.DESCENDING -> {
                if (kneeAngle < SQUAT_BOTTOM_ENTRY) {
                    squatPhase = SquatPhase.AT_BOTTOM
                } else if (kneeAngle > SQUAT_STAND_THRESHOLD) {
                    // Stood back up without reaching depth — cancel rep.
                    squatPhase = SquatPhase.STANDING
                }
            }
            SquatPhase.AT_BOTTOM -> {
                // Hysteresis: must rise 10° before we consider it ascending.
                if (kneeAngle > SQUAT_BOTTOM_ENTRY + 10f) {
                    squatPhase = SquatPhase.ASCENDING
                }
            }
            SquatPhase.ASCENDING -> {
                if (kneeAngle > SQUAT_STAND_THRESHOLD) {
                    squatPhase = SquatPhase.STANDING
                    onRepCompleted(result)
                }
            }
        }
    }

    private fun processShot(result: AnalysisResult) {
        val pose = result.pose
        val side = GeometryUtils.selectBetterSide(pose)
        val ids  = GeometryUtils.indicesFor(side)
        val shoulder = pose[ids.shoulder]
        val wrist    = pose[ids.wrist]

        if (shoulder.confidence < 0.25f || wrist.confidence < 0.25f) return

        // In image coords y increases downward, so wrist ABOVE shoulder = wrist.y < shoulder.y
        val wristAboveShoulder = wrist.y < shoulder.y - SHOT_WRIST_MARGIN

        when (shotPhase) {
            ShotPhase.LOADING -> {
                if (wristAboveShoulder) {
                    shotPhase = ShotPhase.SHOOTING
                    onRepStarted()
                }
            }
            ShotPhase.SHOOTING -> {
                if (!wristAboveShoulder) {
                    shotPhase = ShotPhase.LOADING
                    onRepCompleted(result)
                }
            }
        }
    }

    companion object {
        // Squat thresholds (knee angle in degrees; larger = more extended)
        private const val SQUAT_STAND_THRESHOLD = 155f  // standing tall
        private const val SQUAT_DESCEND_START   = 145f  // beginning of descent
        private const val SQUAT_BOTTOM_ENTRY    = 115f  // at or below parallel

        // Shot threshold (normalized image coords)
        private const val SHOT_WRIST_MARGIN = 0.05f  // wrist must be 5% above shoulder
    }
}
