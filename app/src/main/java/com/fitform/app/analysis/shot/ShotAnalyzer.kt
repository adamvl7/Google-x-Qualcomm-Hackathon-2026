package com.fitform.app.analysis.shot

import com.fitform.app.analysis.GeometryUtils
import com.fitform.app.model.AnalysisResult
import com.fitform.app.model.Keypoint
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Severity
import com.fitform.app.model.Side

/**
 * Front-view basketball jump-shot analyzer.
 *
 * ── Camera positioning ───────────────────────────────────────────────────────
 * Phone should be placed at waist height, ~6–8 feet directly in FRONT of
 * the athlete so the full body is visible face-on.
 *
 * ── Checks ───────────────────────────────────────────────────────────────────
 *   1. Knee bend    — athlete must dip knees before/during the shot.
 *   2. Elbow tuck   — shooting elbow should not flare out sideways ("chicken wing").
 *   3. 90° set      — elbow (shoulder→elbow→wrist) should be ~90° at set-point.
 *
 * ── Cue priority (highest → lowest) ─────────────────────────────────────────
 *   1. Low keypoint confidence  → "Step back / improve lighting"
 *   2. Knee not bent            → "Bend your knees"
 *   3. Elbow flare              → "Elbow in"
 *   4. Elbow not at 90°         → "Elbow at 90°"
 *   5. All checks pass          → "Good shot form"
 *
 * ── Shooting side selection ──────────────────────────────────────────────────
 * In front view both arms are visible. We pick the arm whose wrist is highest
 * (lowest y in image coords) — the raised arm is the shooting arm. When both
 * wrists are low (resting), we pick the higher-confidence side.
 *
 * ── Scoring ──────────────────────────────────────────────────────────────────
 * Starts at 100; each failed check deducts points per SCORE_* constants.
 * Borderline tracking confidence deducts a further 15 pts.
 */
class ShotAnalyzer {

    // Latched true once the knee bend threshold is met in a given shot cycle.
    // Prevents re-penalizing the naturally straightening legs during the jump.
    // Resets to false when the player returns to ready/standing position.
    private var kneeBendAchieved = false

    fun analyze(pose: PoseResult): AnalysisResult {
        if (pose.averageConfidence < 0.30f) {
            return AnalysisResult(
                score = 0,
                cue = "Step back / improve lighting",
                severity = Severity.YELLOW,
                timestampMs = pose.timestampMs,
                pose = pose,
                tracking = false,
            )
        }

        // Shooting arm = whichever wrist is highest (lowest y).
        val leftWrist  = pose[KeypointIndex.LEFT_WRIST]
        val rightWrist = pose[KeypointIndex.RIGHT_WRIST]
        val side = if (leftWrist.confidence >= Keypoint.MIN_CONFIDENCE &&
            leftWrist.y <= rightWrist.y) Side.LEFT else Side.RIGHT
        val ids = GeometryUtils.indicesFor(side)

        val shoulder = pose[ids.shoulder]
        val elbow    = pose[ids.elbow]
        val wrist    = pose[ids.wrist]
        val hip      = pose[ids.hip]
        val knee     = pose[ids.knee]
        val ankle    = pose[ids.ankle]

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
        // Only enter shot analysis when the wrist is clearly above shoulder level
        // (shot is being loaded/released) OR the hips have dropped significantly
        // (player is in the load dip). Requiring wrist above shoulder — not just
        // above hip — prevents the "just standing holding the ball" case from
        // triggering knee-bend feedback prematurely.
        val wristAtSetPoint = wrist.y < shoulder.y   // wrist above shoulder = shot in progress
        val hipDipping      = (hip.y - shoulder.y) > 0.15f
        if (!wristAtSetPoint && !hipDipping) {
            kneeBendAchieved = false   // reset for the next shot cycle
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

        // ── Check 1: Knee bend ────────────────────────────────────────────────
        // Check during the load (hip dipping) OR set-point phase. The dip happens
        // before the wrist reaches shoulder level, so we can't gate on wristAtSetPoint.
        // Once a sufficient dip is detected, latch so the straightening jump legs
        // don't re-trigger the cue.
        if (!kneeBendAchieved) {
            val kneeAngle = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle)
            if (kneeAngle <= KNEE_BEND_LIMIT) {
                kneeBendAchieved = true   // good dip — latch for this shot cycle
            } else if (wristAtSetPoint) {
                // Only penalize if the wrist is already at set-point and knees still
                // haven't bent — not during the very start of a hip dip.
                score -= SCORE_KNEE_BEND
                cues += "Bend your knees" to Severity.YELLOW
            }
        }

        // ── Check 2: Elbow tuck — no chicken wing ─────────────────────────────
        // In front view, the shooting elbow should sit directly below the shoulder
        // (upper arm roughly vertical). If the elbow drifts sideways of the shoulder
        // the upper arm is flaring out — classic chicken wing.
        // Measure: horizontal distance between shoulder and elbow.
        val elbowFlare = GeometryUtils.absHorizontalOffset(shoulder, elbow)
        if (elbowFlare > ELBOW_FLARE_LIMIT) {
            score -= SCORE_ELBOW_TUCK
            cues += "Elbow in" to Severity.YELLOW
        }

        // ── Check 3: 90° elbow at set-point ──────────────────────────────────
        // At a true 90° set-point the forearm points straight at the camera, so
        // the elbow and wrist share nearly the same x-coordinate in front view.
        // If the wrist is displaced sideways of the elbow the forearm is angled —
        // the elbow is NOT at 90°.
        // Gate: only check while wrist is at or above shoulder level (set-point window).
        val atSetPoint = wrist.y <= shoulder.y + 0.05f
        if (atSetPoint) {
            val forearmLateral = GeometryUtils.absHorizontalOffset(elbow, wrist)
            if (forearmLateral > ELBOW_ANGLE_TOLERANCE) {
                score -= SCORE_ELBOW_ANGLE
                cues += "Elbow at 90°" to Severity.YELLOW
            }
        }

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

    companion object {
        /** Hip→knee→ankle angle above which the knee load is insufficient. */
        private const val KNEE_BEND_LIMIT = 160f

        /** Max lateral shoulder-to-elbow offset (normalized width) before chicken-wing cue.
         *  Upper arm should be roughly vertical — elbow directly below shoulder. */
        private const val ELBOW_FLARE_LIMIT = 0.08f

        /** Max lateral elbow-to-wrist offset at set-point before "Elbow at 90°" cue.
         *  At true 90° the forearm points at the camera, so elbow x ≈ wrist x. */
        private const val ELBOW_ANGLE_TOLERANCE = 0.07f

        private const val SCORE_KNEE_BEND   = 30
        private const val SCORE_ELBOW_TUCK  = 35
        private const val SCORE_ELBOW_ANGLE = 35
        private const val SCORE_LOW_CONF    = 15
    }
}
