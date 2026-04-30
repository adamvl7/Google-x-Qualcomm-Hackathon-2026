package com.fitform.app.analysis.shot

import com.fitform.app.analysis.GeometryUtils
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Severity
import com.fitform.app.model.Side
import kotlin.math.abs

/**
 * Side-view basketball jump-shot analyzer using the B.E.F. framework.
 *
 * ── Camera positioning ───────────────────────────────────────────────────────
 * Phone should be placed at waist height, ~2–3 m away, pointing at the
 * athlete's shooting-hand side so the shooting arm is clearly visible.
 *
 * ── B.E.F. Framework ─────────────────────────────────────────────────────────
 *   B — Balance      Knee bend on the load phase + level ankle landing
 *   E — Elbow        Elbow directly under the ball (wrist) at set-point
 *   F — Follow-thru  Full arm extension at release (high arc entry angle)
 *
 * We intentionally DO NOT evaluate: ball grip, guide-hand placement,
 * finger roll, wrist snap direction, or eye-on-rim tracking. Those
 * require either ball-tracking vision or wrist IMU data that pose
 * estimation alone cannot provide reliably on-device.
 *
 * ── Cue priority (highest → lowest) ─────────────────────────────────────────
 *   1. Low keypoint confidence    → "Step back / improve lighting"
 *   2. Elbow misaligned at set    → "Elbow in"
 *   3. Insufficient arm extension → "Release higher"
 *   4. Shallow knee load          → "More knee bend"
 *   5. Unbalanced landing         → "Land balanced"
 *   6. All checks pass            → "Good shot form"
 *
 * ── Shooting side selection ──────────────────────────────────────────────────
 * Because the athlete stands sideways, one arm faces the camera and the
 * other faces away — occluded. We pick whichever side has the higher
 * combined elbow + wrist confidence, which reliably selects the shooting
 * arm that is most visible in a side-view setup.
 *
 * ── Scoring ──────────────────────────────────────────────────────────────────
 * Starts at 100; each failed check deducts points per SCORE_* constants.
 * Borderline tracking confidence deducts a further 15 pts.
 */
class ShotAnalyzer {

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

        // Lock to the shooting side (the arm that is most visible in side view).
        val side = pickShootingSide(pose)
        val ids  = GeometryUtils.indicesFor(side)

        val shoulder = pose[ids.shoulder]
        val elbow    = pose[ids.elbow]
        val wrist    = pose[ids.wrist]
        val hip      = pose[ids.hip]
        val knee     = pose[ids.knee]
        val ankle    = pose[ids.ankle]

        // Secondary guard on the six joints we measure.
        // 0.25 threshold — INT8 MoveNet produces lower raw confidence values than float32,
        // and side-view occludes joints on the far side. Proceed with penalty below 0.55.
        val coreConfidence = GeometryUtils.confidenceAverage(
            listOf(shoulder, elbow, wrist, hip, knee, ankle)
        )
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

        // ── Ready-state detection ─────────────────────────────────────────────
        // If the wrist is well below the shoulder and the legs aren't loaded,
        // the person is standing at rest — show a neutral ready cue.
        val hipLoading = (hip.y - shoulder.y) > 0.18f
        val wristResting = wrist.y > shoulder.y + 0.05f
        if (wristResting && !hipLoading) {
            return AnalysisResult(
                score = 100,
                cue = "Ready — begin your shot",
                severity = Severity.GREEN,
                timestampMs = pose.timestampMs,
                pose = pose,
            )
        }

        var score = 100
        val cues = mutableListOf<Pair<String, Severity>>()

        // ── Check E: Elbow alignment at set-point ─────────────────────────────
        // A correct shooting motion has the elbow stacked directly under the
        // wrist and ball — forming an "L" shape. Horizontal misalignment means
        // the elbow is flared out ("chicken wing"), causing the ball to push
        // sideways and the shot to miss left/right.
        //
        // Threshold: 0.08 normalized width ≈ 4–6 cm at typical filming distance.
        // Below this margin the elbow is acceptably aligned for a recreational
        // shooter; above it the deviation is visible and correctable with a cue.
        val elbowOffset = GeometryUtils.absHorizontalOffset(elbow, wrist)
        if (elbowOffset > ELBOW_OFFSET_LIMIT) {
            score -= SCORE_ELBOW
            cues += "Elbow in" to Severity.YELLOW
        }

        // ── Check F: Release / arm extension ──────────────────────────────────
        // The shoulder→elbow→wrist angle at release:
        //   • 180° = fully straight arm (textbook follow-through)
        //   • ~160–175° = typical NBA release (Klay Thompson, Steph Curry)
        //   •  130° = minimum threshold; below this the athlete is clearly
        //             "pushing" short without extending, flat low-arc shot
        //
        // Gate: wrist must be at least 12% of frame height above shoulder
        // (wrist.y < shoulder.y - 0.12). A tighter gate avoids firing during
        // the early arm raise before the athlete reaches the release window.
        val wristAboveShoulder = wrist.y < shoulder.y - 0.12f
        if (wristAboveShoulder) {
            val releaseAngle = GeometryUtils.angleBetweenThreePoints(shoulder, elbow, wrist)
            if (releaseAngle < RELEASE_ANGLE_MIN) {
                score -= SCORE_RELEASE
                cues += "Release higher" to Severity.YELLOW
            }
        }

        // ── Check B (legs): Knee bend during the load phase ──────────────────
        // The loading dip stores elastic energy in the legs that powers the
        // jump and transfers force into the shot. The hip→knee→ankle angle:
        //   • 90°  = deep squat position (too deep for a shot)
        //   • ~130–145° = typical shooting stance dip
        //   • 155° = nearly straight; less than 25° of bend — barely any load
        //
        // Gate: only check when the hip is meaningfully lower than the shoulder
        // (hip.y > shoulder.y + 0.18), which indicates the athlete has initiated
        // the dip rather than just standing at rest.
        val hipLoaded = (hip.y - shoulder.y) > 0.18f
        if (hipLoaded) {
            val kneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
            if (kneeAngle > KNEE_BEND_LIMIT) {
                score -= SCORE_KNEE_BEND
                cues += "More knee bend" to Severity.YELLOW
            }
        }

        // ── Check B (landing): Balanced ankle landing ─────────────────────────
        // After a jump shot the athlete should land with both feet level to
        // distribute impact and avoid ankle/knee stress. We compare the y
        // positions of left and right ankle keypoints.
        //
        // Threshold: 0.05 normalized frame height ≈ 3–4 cm difference. Only
        // evaluated when both ankles have reasonable confidence (> 0.4), so
        // we don't penalise when one ankle is occluded by the body.
        val leftAnkle  = pose[KeypointIndex.LEFT_ANKLE]
        val rightAnkle = pose[KeypointIndex.RIGHT_ANKLE]
        if (leftAnkle.confidence > 0.4f && rightAnkle.confidence > 0.4f) {
            val ankleTilt = GeometryUtils.absVerticalOffset(leftAnkle, rightAnkle)
            if (ankleTilt > LANDING_TILT_LIMIT) {
                score -= SCORE_LANDING
                cues += "Land balanced" to Severity.YELLOW
            }
        }

        // Mild deduction for borderline confidence (passes the 0.40 guard but
        // still noisy enough to reduce scoring reliability).
        if (coreConfidence < 0.55f) score -= SCORE_LOW_CONF

        score = score.coerceIn(0, 100)

        val (cue, severity) = when {
            cues.isEmpty() -> "Good shot form" to Severity.GREEN
            else -> {
                val first = cues.first()
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
     * Selects the shooting side as whichever arm (elbow + wrist pair) has the
     * higher total confidence. In a side-view setup the shooting arm faces the
     * camera and is reliably tracked; the guide arm faces away and has lower
     * confidence scores, so this heuristic consistently selects the right side
     * without requiring the user to specify their dominant hand.
     */
    private fun pickShootingSide(pose: PoseResult): Side {
        val leftScore  = pose[KeypointIndex.LEFT_ELBOW].confidence + pose[KeypointIndex.LEFT_WRIST].confidence
        val rightScore = pose[KeypointIndex.RIGHT_ELBOW].confidence + pose[KeypointIndex.RIGHT_WRIST].confidence
        return if (rightScore >= leftScore) Side.RIGHT else Side.LEFT
    }

    companion object {
        // Geometry thresholds — all in the same unit as MoveNet keypoint
        // coordinates: normalized [0, 1] where 1 = full frame dimension.
        // Angles are in degrees.

        /**
         * Maximum horizontal offset (normalized width) between elbow and wrist
         * before the "Elbow in" cue triggers. 0.08 ≈ one hand-width at typical
         * filming distance.
         */
        private const val ELBOW_OFFSET_LIMIT = 0.08f

        /**
         * Minimum shoulder→elbow→wrist angle at release. Below 130° the athlete
         * is clearly not extending — NBA shooters (Klay, Curry) release at 160–175°.
         * 130° gives recreational players room while still catching genuinely short
         * releases. Gate is also tightened to wrist 12% above shoulder.
         */
        private const val RELEASE_ANGLE_MIN = 130f

        /**
         * Maximum hip→knee→ankle angle during the loading phase. Above 155°
         * means the athlete has barely bent their knees — insufficient to
         * generate vertical force for the jump.
         */
        private const val KNEE_BEND_LIMIT = 155f

        /**
         * Maximum ankle y-offset (normalized height) for a balanced landing.
         * 0.05 ≈ 3–4 cm at typical filming distance — a clearly lopsided
         * landing is detectable and worth cueing.
         */
        private const val LANDING_TILT_LIMIT = 0.05f

        // Score deductions per failed check.
        private const val SCORE_ELBOW     = 25
        private const val SCORE_RELEASE   = 20
        private const val SCORE_KNEE_BEND = 20
        private const val SCORE_LANDING   = 20
        private const val SCORE_LOW_CONF  = 15
    }
}
