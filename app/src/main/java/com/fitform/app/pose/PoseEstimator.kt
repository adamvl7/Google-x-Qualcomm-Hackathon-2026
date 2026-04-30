package com.fitform.app.pose

import androidx.camera.core.ImageProxy
import com.fitform.app.model.PoseResult

/**
 * Abstraction over the on-device pose model so the rest of the app
 * can be developed and demoed even when a real LiteRT model isn't
 * loaded (see [MockPoseEstimator]).
 *
 * The contract: implementations MUST close [frame] before returning
 * (CameraX requires it on the analyzer thread).
 */
interface PoseEstimator {
    /**
     * Estimate pose from a single camera frame. Returns [PoseResult]
     * with normalized [0,1] coords in image space (already rotated to
     * upright), or null if the frame can't be analyzed.
     */
    suspend fun estimatePose(frame: ImageProxy): PoseResult?

    /** Release native resources. Safe to call multiple times. */
    fun close()
}
