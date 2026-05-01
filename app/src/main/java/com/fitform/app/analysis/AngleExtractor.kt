package com.fitform.app.analysis

import com.fitform.app.model.ExerciseMode
import com.fitform.app.model.KeypointIndex
import com.fitform.app.model.PoseResult
import com.fitform.app.model.Side
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Extracts a 4-element normalized feature vector from a [PoseResult] for use
 * by [FormClassifier]. The same geometric quantities the rule-based analyzers
 * compute, packaged for ML inference.
 *
 * All values are normalized to approximately [0, 1] using the same thresholds
 * the analyzers use, so the classifier sees the same scale during training and
 * at inference time.
 *
 * GYM (squat) features:
 *   [0] kneeAngle   — hip→knee→ankle angle / 180°
 *   [1] trunkLean   — shoulder→hip vertical deviation / 90°
 *   [2] kneeForward — abs(ankle→knee horizontal offset) / 0.30 (clamped to 1)
 *   [3] hipBalance  — abs(left hip y − right hip y) / 0.10 (clamped to 1)
 *
 * SHOT (jump shot) features:
 *   [0] elbowOffset  — abs(elbow−wrist horizontal) / 0.30 (clamped to 1)
 *   [1] releaseAngle — shoulder→elbow→wrist angle / 180°
 *   [2] kneeBend     — hip→knee→ankle angle / 180°
 *   [3] landingTilt  — abs(left ankle y − right ankle y) / 0.10 (clamped to 1)
 */
object AngleExtractor {

    fun extract(pose: PoseResult, mode: ExerciseMode): FloatArray = when (mode) {
        ExerciseMode.GYM  -> extractSquat(pose)
        ExerciseMode.SHOT -> extractShot(pose)
    }

    private fun extractSquat(pose: PoseResult): FloatArray {
        val side = GeometryUtils.selectBetterSide(pose)
        val ids  = GeometryUtils.indicesFor(side)

        val shoulder = pose[ids.shoulder]
        val hip      = pose[ids.hip]
        val knee     = pose[ids.knee]
        val ankle    = pose[ids.ankle]
        val leftHip  = pose[KeypointIndex.LEFT_HIP]
        val rightHip = pose[KeypointIndex.RIGHT_HIP]

        val kneeAngle   = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle) / 180f
        val trunkLean   = trunkLeanDegrees(shoulder, hip) / 90f
        val kneeForward = (abs(GeometryUtils.horizontalOffset(ankle, knee)) / 0.30f).coerceAtMost(1f)
        val hipBalance  = (GeometryUtils.absVerticalOffset(leftHip, rightHip) / 0.10f).coerceAtMost(1f)

        return floatArrayOf(kneeAngle, trunkLean, kneeForward, hipBalance)
    }

    private fun extractShot(pose: PoseResult): FloatArray {
        val leftScore  = pose[KeypointIndex.LEFT_ELBOW].confidence + pose[KeypointIndex.LEFT_WRIST].confidence
        val rightScore = pose[KeypointIndex.RIGHT_ELBOW].confidence + pose[KeypointIndex.RIGHT_WRIST].confidence
        val side = if (rightScore >= leftScore) Side.RIGHT else Side.LEFT
        val ids  = GeometryUtils.indicesFor(side)

        val shoulder   = pose[ids.shoulder]
        val elbow      = pose[ids.elbow]
        val wrist      = pose[ids.wrist]
        val hip        = pose[ids.hip]
        val knee       = pose[ids.knee]
        val ankle      = pose[ids.ankle]
        val leftAnkle  = pose[KeypointIndex.LEFT_ANKLE]
        val rightAnkle = pose[KeypointIndex.RIGHT_ANKLE]

        val elbowOffset  = (GeometryUtils.absHorizontalOffset(elbow, wrist) / 0.30f).coerceAtMost(1f)
        val releaseAngle = GeometryUtils.angleBetweenThreePoints(shoulder, elbow, wrist) / 180f
        val kneeBend     = GeometryUtils.angleBetweenThreePoints(hip, knee, ankle) / 180f
        val landingTilt  = (GeometryUtils.absVerticalOffset(leftAnkle, rightAnkle) / 0.10f).coerceAtMost(1f)

        return floatArrayOf(elbowOffset, releaseAngle, kneeBend, landingTilt)
    }

    private fun trunkLeanDegrees(
        shoulder: com.fitform.app.model.Keypoint,
        hip: com.fitform.app.model.Keypoint,
    ): Float {
        val dx = abs(shoulder.x - hip.x)
        val dy = abs(shoulder.y - hip.y).coerceAtLeast(0.0001f)
        return Math.toDegrees(atan2(dx, dy).toDouble()).toFloat()
    }
}
