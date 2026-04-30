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
 *   1. Low keypoint confidence     → "Step back / improve lighting"
 *   2. Excessive forward knee travel → "Watch knee tracking"
 *   3. Excessive trunk forward lean  → "Chest up"
 *   4. Insufficient squat depth      → "Go deeper"
 *   5. Lateral hip imbalance         → "Stay balanced"
 *   6. All checks pass               → "Good rep"
 *
 * ── Scoring ──────────────────────────────────────────────────────────────────
 * Starts at 100; each failed check deducts points per SCORE_* constants.
 * Borderline tracking confidence deducts a further 15 pts.
 */
class SquatAnalyzer {

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
        // If the knee is nearly straight the person is standing at rest.
        // Show a neutral ready-state cue rather than form feedback.
        val standingKneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
        if (standingKneeAngle > STANDING_KNEE_ANGLE) {
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
        // Threshold: 0.10 in normalized [0,1] frame width ≈ 5–8 cm at typical
        // filming distance (~1.5 m). At this distance the full body fills ~60%
        // of frame height; 10% of width corresponds to roughly one shoe-length
        // of forward drift, which coaches commonly cite as the correction boundary.
        val kneeForward = GeometryUtils.horizontalOffset(ankle, knee)
        if (abs(kneeForward) > KNEE_TRAVEL_LIMIT) {
            score -= SCORE_KNEE_TRAVEL
            cues += "Watch knee tracking" to Severity.YELLOW
        }

        // ── Check 2: Trunk / back angle from vertical ─────────────────────────
        // We measure the angle between the shoulder→hip segment and a true
        // vertical line. 0° = perfectly upright, 90° = fully horizontal.
        // >45° from vertical indicates the torso is leaning too far forward,
        // overloading the lower back and reducing quad engagement. The 45°
        // limit matches the upper bound cited in most strength-coaching literature
        // for high-bar back squat; low-bar squats allow slightly more lean but
        // the same cue applies.
        val trunkAngle = trunkLeanDegrees(shoulder, hip)
        if (trunkAngle > BACK_LEAN_LIMIT) {
            score -= SCORE_BACK_LEAN
            cues += "Chest up" to Severity.YELLOW
        }

        // ── Check 3: Squat depth ──────────────────────────────────────────────
        // The hip→knee→ankle angle at the knee joint:
        //   • 90°  = thighs parallel to the floor (competition parallel squat)
        //   • 110° = just above parallel — the most common "didn't go deep enough" cut-off
        //   • 180° = standing straight
        // We only apply this check when the hip is clearly lower than the
        // shoulder (hip.y > shoulder.y + 0.25 in image coords where y increases
        // downward) to avoid flagging the check while the athlete is still
        // standing and approaching the bottom of the rep.
        val kneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
        val descending = (hip.y - shoulder.y) > 0.25f
        if (descending && kneeAngle > DEPTH_KNEE_ANGLE_LIMIT) {
            score -= SCORE_DEPTH
            cues += "Go deeper" to Severity.YELLOW
        }

        // ── Check 4: Lateral hip balance ──────────────────────────────────────
        // Compare left and right hip y-coordinates. A tilt > 0.05 (5% of frame
        // height ≈ 3–4 cm at typical distance) indicates the athlete is leaning
        // or favouring one side — common when one hip flexor is tighter or
        // one leg is doing more work. The same threshold is used for landing
        // balance in ShotAnalyzer to maintain consistency across modes.
        val leftHip  = pose[KeypointIndex.LEFT_HIP]
        val rightHip = pose[KeypointIndex.RIGHT_HIP]
        val balanceOffset = GeometryUtils.absVerticalOffset(leftHip, rightHip)
        if (balanceOffset > BALANCE_LIMIT) {
            score -= SCORE_BALANCE
            cues += "Stay balanced" to Severity.YELLOW
        }

        // Mild deduction when tracking confidence is borderline (0.40–0.55).
        // Score is still displayed but with a 15-pt penalty to reflect uncertainty.
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
     * Returns the angle (degrees) between the shoulder→hip segment and a
     * true vertical line. 0° = upright spine, 90° = horizontal torso.
     *
     * Implementation: atan(Δx / Δy). When the shoulder is directly above
     * the hip Δx ≈ 0 so the angle approaches 0°. The coerceAtLeast guard
     * prevents division by zero on perfectly vertical poses.
     */
    private fun trunkLeanDegrees(
        shoulder: com.fitform.app.model.Keypoint,
        hip: com.fitform.app.model.Keypoint,
    ): Float {
        val dx = abs(shoulder.x - hip.x)
        val dy = abs(shoulder.y - hip.y).coerceAtLeast(0.0001f)
        return Math.toDegrees(kotlin.math.atan2(dx, dy).toDouble()).toFloat()
    }

    companion object {
        // Geometry thresholds — all in the same unit as MoveNet keypoint
        // coordinates: normalized [0, 1] where 1 = full frame dimension.
        // Angles are in degrees.

        /** Knee angle above which the person is considered standing at rest. */
        private const val STANDING_KNEE_ANGLE = 155f

        /** Knee x must not exceed ankle x by more than this (normalized width). */
        private const val KNEE_TRAVEL_LIMIT = 0.10f

        /** Trunk forward lean limit in degrees from vertical. */
        private const val BACK_LEAN_LIMIT = 45f

        /**
         * Knee angle (hip→knee→ankle) above which squat depth is insufficient.
         * 110° ≈ just above parallel; below this is a passing rep.
         */
        private const val DEPTH_KNEE_ANGLE_LIMIT = 110f

        /** Max left/right hip y-offset before triggering the balance cue. */
        private const val BALANCE_LIMIT = 0.05f

        // Score deductions per failed check.
        private const val SCORE_KNEE_TRAVEL = 25
        private const val SCORE_BACK_LEAN   = 20
        private const val SCORE_DEPTH       = 25
        private const val SCORE_BALANCE     = 15
        private const val SCORE_LOW_CONF    = 15
    }
}
