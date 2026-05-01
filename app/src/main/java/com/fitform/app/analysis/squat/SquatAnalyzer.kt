package com.fitform.app.analysis.squat

import com.fitform.app.analysis.GeometryUtils
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Severity
import kotlin.math.abs

/**
 * Side-view squat analyzer.
 *
 * ── Camera positioning ───────────────────────────────────────────────────────
 * Phone should be placed at roughly hip height, ~1.5–2 m away, pointing
 * at the athlete's RIGHT side so the full body is visible in frame.
 *
 * ── Why forward knee travel instead of knee valgus? ─────────────────────────
 * Knee valgus (medial collapse / "knees caving") is a FRONTAL-PLANE
 * phenomenon — the knee moves toward the midline. Detecting it reliably
 * requires a FRONT-VIEW camera, where you can compare knee and ankle
 * x-positions across the body's axis.
 *
 * With a SIDE-VIEW camera we can instead measure SAGITTAL-PLANE knee
 * travel: how far the knee has drifted forward of the ankle. Excessive
 * forward knee travel is:
 *   • biomechanically correlated with the same posterior-chain weakness
 *     that causes valgus (weak glutes / limited ankle dorsiflexion)
 *   • a recognized coaching cue in strength training ("keep your shins
 *     more vertical")
 *   • directly detectable from the keypoint x-coordinates in side view
 *
 * ── Cue priority (highest → lowest) ─────────────────────────────────────────
 *   1. Low keypoint confidence       → "Step back / improve lighting"
 *   2. Excessive forward knee travel → "Watch knee tracking"
 *   3. Back/shin angle mismatch      → "Chest up" or "Sit back"
 *   4. Insufficient squat depth      → "Go deeper"
 *   5. Lateral hip imbalance         → "Stay balanced"
 *   6. All checks pass               → "Good rep"
 *
 * ── Scoring ──────────────────────────────────────────────────────────────────
 * Starts at 100; each failed check deducts points per SCORE_* constants.
 * Borderline tracking confidence deducts a further 15 pts.
 */
class SquatAnalyzer {

    // Per-check consecutive frame counters — an issue only counts once it has
    // been detected for CONFIRM_FRAMES frames in a row. Resets on each good frame.
    private var kneeFrames    = 0
    private var postureFrames = 0
    private var depthFrames   = 0
    private var balanceFrames = 0

    fun analyze(pose: PoseResult): AnalysisResult {
        // Guard: reject frames where overall pose confidence is too low to trust.
        if (!GeometryUtils.sideViewBodyVisibilityCheck(pose) || pose.averageConfidence < 0.30f) {
            return AnalysisResult(
                score = 0,
                cue = "Step back / improve lighting",
                severity = Severity.YELLOW,
                timestampMs = pose.timestampMs,
                pose = pose,
                tracking = false,
            )
        }

        // Pick whichever side (left / right) has better keypoint confidence.
        val side = GeometryUtils.selectBetterSide(pose)
        val ids = GeometryUtils.indicesFor(side)

        val shoulder = pose[ids.shoulder]
        val hip      = pose[ids.hip]
        val knee     = pose[ids.knee]
        val ankle    = pose[ids.ankle]

        // Secondary guard on the four joints we actually measure.
        // 0.25 threshold — INT8 MoveNet produces lower raw confidence values than float32,
        // and side-view partially occludes some joints. Anything below 0.25 is too noisy
        // to score; between 0.25–0.55 we proceed with a score penalty applied below.
        val coreConfidence = GeometryUtils.confidenceAverage(listOf(shoulder, hip, knee, ankle))
        if (coreConfidence < 0.25f) {
            return AnalysisResult(
                score = 0,
                cue = "Step back / improve lighting",
                severity = Severity.YELLOW,
                timestampMs = pose.timestampMs,
                pose = pose,
                tracking = false,
            )
        }

        // ── Standing detection ────────────────────────────────────────────────
        val standingKneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
        if (standingKneeAngle > STANDING_KNEE_ANGLE) {
            kneeFrames = 0; postureFrames = 0; depthFrames = 0; balanceFrames = 0
            return AnalysisResult(
                score = 100,
                cue = "Standing — begin your squat",
                severity = Severity.GREEN,
                timestampMs = pose.timestampMs,
                pose = pose,
            )
        }

        var score = 100
        val cues = mutableListOf<Pair<String, Severity>>()

        // ── Check 1: Forward knee travel ─────────────────────────────────────
        val kneeForward = GeometryUtils.horizontalOffset(ankle, knee)
        if (abs(kneeForward) > KNEE_TRAVEL_LIMIT) {
            kneeFrames++
            if (kneeFrames >= CONFIRM_FRAMES) {
                score -= SCORE_KNEE_TRAVEL
                cues += "Watch knee tracking" to Severity.YELLOW
            }
        } else {
            kneeFrames = 0
        }

        // ── Check 2: Back vs shin parallel ────────────────────────────────────
        val trunkAngle = leanFromVerticalDegrees(shoulder, hip)
        val shinAngle  = leanFromVerticalDegrees(knee, ankle)
        val postureDiff = trunkAngle - shinAngle
        if (abs(postureDiff) > POSTURE_DIFF_LIMIT) {
            postureFrames++
            if (postureFrames >= CONFIRM_FRAMES) {
                score -= SCORE_BACK_LEAN
                cues += if (postureDiff > 0) "Chest up" to Severity.YELLOW
                        else "Sit back" to Severity.YELLOW
            }
        } else {
            postureFrames = 0
        }

        // ── Check 3: Squat depth ──────────────────────────────────────────────
        // 110° threshold: thighs at or just above parallel — practical for
        // non-competition athletes. 90° (full parallel) is too strict for casual use.
        val kneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
        if (kneeAngle > DEPTH_KNEE_ANGLE_LIMIT) {
            depthFrames++
            if (depthFrames >= CONFIRM_FRAMES) {
                score -= SCORE_DEPTH
                cues += "Too shallow" to Severity.YELLOW
            }
        } else {
            depthFrames = 0
        }

        // ── Check 4: Lateral hip balance ──────────────────────────────────────
        val leftHip  = pose[KeypointIndex.LEFT_HIP]
        val rightHip = pose[KeypointIndex.RIGHT_HIP]
        val balanceOffset = GeometryUtils.absVerticalOffset(leftHip, rightHip)
        if (balanceOffset > BALANCE_LIMIT) {
            balanceFrames++
            if (balanceFrames >= CONFIRM_FRAMES) {
                score -= SCORE_BALANCE
                cues += "Stay balanced" to Severity.YELLOW
            }
        } else {
            balanceFrames = 0
        }

        // Small deduction for borderline confidence — reduced from 15 to 8
        // so real-world lighting doesn't tank scores on otherwise good reps.
        if (coreConfidence < 0.55f) score -= SCORE_LOW_CONF

        score = score.coerceIn(0, 100)

        val (cue, severity) = when {
            cues.isEmpty() -> "Good rep" to Severity.GREEN
            else -> {
                val first = cues.first()
                // Escalate to RED when score is critically low (≥ two major issues).
                val sev = if (score < 70) Severity.RED else first.second
                first.first to sev
            }
        }

        return AnalysisResult(
            score = score,
            cue = cue,
            severity = severity,
            timestampMs = pose.timestampMs,
            pose = pose,
        )
    }

    /**
     * Returns the angle (degrees) between the segment [top]→[bottom] and a
     * true vertical line. 0° = perfectly vertical, 90° = horizontal.
     *
     * Used for both the torso (shoulder→hip) and the shin (knee→ankle) so the
     * two lean values can be directly compared.
     */
    private fun leanFromVerticalDegrees(
        top: com.fitform.app.model.Keypoint,
        bottom: com.fitform.app.model.Keypoint,
    ): Float {
        val dx = abs(top.x - bottom.x)
        val dy = abs(top.y - bottom.y).coerceAtLeast(0.0001f)
        return Math.toDegrees(kotlin.math.atan2(dx, dy).toDouble()).toFloat()
    }

    companion object {
        // Geometry thresholds — all in the same unit as MoveNet keypoint
        // coordinates: normalized [0, 1] where 1 = full frame dimension.
        // Angles are in degrees.

        /** Frames a check must fire consecutively before it counts against the score. */
        private const val CONFIRM_FRAMES = 6  // ~200ms at 30fps

        private const val STANDING_KNEE_ANGLE = 155f
        private const val KNEE_TRAVEL_LIMIT   = 0.13f  // slightly more forgiving
        private const val POSTURE_DIFF_LIMIT  = 25f    // wider band — pose noise can eat 15°
        private const val DEPTH_KNEE_ANGLE_LIMIT = 110f // just-above-parallel is good enough
        private const val BALANCE_LIMIT       = 0.07f  // side-view balance is noisy

        private const val SCORE_KNEE_TRAVEL = 20
        private const val SCORE_BACK_LEAN   = 15
        private const val SCORE_DEPTH       = 20
        private const val SCORE_BALANCE     = 10
        private const val SCORE_LOW_CONF    = 8
    }
}
