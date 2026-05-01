"""
extract_angles_from_videos.py
─────────────────────────────
Extracts biomechanical angle features from exercise videos using MediaPipe
and appends labeled rows to a CSV training file.

Usage
─────
  pip install mediapipe opencv-python numpy

  # Put videos in a folder, named with a prefix:
  #   squat_good_*.mp4   → label 1.0 (good form)
  #   squat_bad_*.mp4    → label 0.0 (bad form)
  #   shot_good_*.mp4    → label 1.0
  #   shot_bad_*.mp4     → label 0.0

  python extract_angles_from_videos.py \
      --input_dir  ./videos/squat \
      --output_csv ./training_data/squat_training.csv \
      --mode       squat \
      --fps        10          # sample every Nth frame (10fps from 30fps video)

  python extract_angles_from_videos.py \
      --input_dir  ./videos/shot \
      --output_csv ./training_data/shot_training.csv \
      --mode       shot

Output CSV columns
──────────────────
  Squat: knee_angle, trunk_lean, knee_forward, hip_balance, label
  Shot:  elbow_offset, release_angle, knee_bend, landing_tilt, label

  label = 1.0 (good form) | 0.0 (bad form)
"""

import argparse
import csv
import math
import os
import sys

import cv2
import mediapipe as mp
import numpy as np

mp_pose = mp.solutions.pose

# ── Geometry helpers ───────────────────────────────────────────────────────────

def _landmark(results, idx):
    lm = results.pose_landmarks.landmark[idx]
    return lm.x, lm.y, lm.visibility


def angle_three_points(ax, ay, bx, by, cx, cy) -> float:
    """Angle in degrees at vertex B."""
    v1 = (ax - bx, ay - by)
    v2 = (cx - bx, cy - by)
    cos_theta = (v1[0]*v2[0] + v1[1]*v2[1]) / (
        math.hypot(*v1) * math.hypot(*v2) + 1e-9
    )
    return math.degrees(math.acos(max(-1.0, min(1.0, cos_theta))))


def trunk_lean(sx, sy, hx, hy) -> float:
    """Degrees from vertical of the shoulder→hip segment."""
    dx = abs(sx - hx)
    dy = max(abs(sy - hy), 1e-4)
    return math.degrees(math.atan2(dx, dy))


# ── Feature extraction ─────────────────────────────────────────────────────────

L = mp_pose.PoseLandmark

def extract_squat(results) -> list[float] | None:
    """Returns [knee_angle/180, trunk_lean/90, knee_forward/0.3, hip_balance/0.1]."""
    try:
        lx = {k: _landmark(results, k) for k in [
            L.LEFT_SHOULDER, L.LEFT_HIP, L.LEFT_KNEE, L.LEFT_ANKLE,
            L.RIGHT_SHOULDER, L.RIGHT_HIP, L.RIGHT_KNEE, L.RIGHT_ANKLE,
        ]}
        # Pick the side with higher hip+knee+ankle visibility
        left_conf  = lx[L.LEFT_HIP][2]  + lx[L.LEFT_KNEE][2]  + lx[L.LEFT_ANKLE][2]
        right_conf = lx[L.RIGHT_HIP][2] + lx[L.RIGHT_KNEE][2] + lx[L.RIGHT_ANKLE][2]
        if left_conf >= right_conf:
            sh, hi, kn, an = L.LEFT_SHOULDER, L.LEFT_HIP, L.LEFT_KNEE, L.LEFT_ANKLE
        else:
            sh, hi, kn, an = L.RIGHT_SHOULDER, L.RIGHT_HIP, L.RIGHT_KNEE, L.RIGHT_ANKLE

        sx, sy, _ = lx[sh]; hx, hy, _ = lx[hi]
        kx, ky, _ = lx[kn]; ax, ay, _ = lx[an]
        lhx, lhy, _ = lx[L.LEFT_HIP]; rhx, rhy, _ = lx[L.RIGHT_HIP]

        knee_angle   = angle_three_points(hx,hy, kx,ky, ax,ay) / 180.0
        trunk        = trunk_lean(sx, sy, hx, hy) / 90.0
        knee_fwd     = min(abs(kx - ax) / 0.30, 1.0)
        hip_balance  = min(abs(lhy - rhy) / 0.10, 1.0)

        return [knee_angle, trunk, knee_fwd, hip_balance]
    except Exception:
        return None


def extract_shot(results) -> list[float] | None:
    """Returns [elbow_offset/0.3, release_angle/180, knee_bend/180, landing_tilt/0.1]."""
    try:
        needed = [
            L.LEFT_ELBOW, L.LEFT_WRIST, L.RIGHT_ELBOW, L.RIGHT_WRIST,
            L.LEFT_SHOULDER, L.LEFT_HIP, L.LEFT_KNEE, L.LEFT_ANKLE,
            L.RIGHT_SHOULDER, L.RIGHT_HIP, L.RIGHT_KNEE, L.RIGHT_ANKLE,
            L.LEFT_ANKLE, L.RIGHT_ANKLE,
        ]
        lx = {k: _landmark(results, k) for k in needed}

        left_arm  = lx[L.LEFT_ELBOW][2]  + lx[L.LEFT_WRIST][2]
        right_arm = lx[L.RIGHT_ELBOW][2] + lx[L.RIGHT_WRIST][2]
        if right_arm >= left_arm:
            sh, el, wr = L.RIGHT_SHOULDER, L.RIGHT_ELBOW, L.RIGHT_WRIST
            hi, kn, an = L.RIGHT_HIP, L.RIGHT_KNEE, L.RIGHT_ANKLE
        else:
            sh, el, wr = L.LEFT_SHOULDER, L.LEFT_ELBOW, L.LEFT_WRIST
            hi, kn, an = L.LEFT_HIP, L.LEFT_KNEE, L.LEFT_ANKLE

        sx, sy, _ = lx[sh]; ex_, ey, _ = lx[el]; wsx, wsy, _ = lx[wr]
        hx, hy, _ = lx[hi]; kx, ky, _ = lx[kn]; ax, ay, _ = lx[an]
        lax, lay, _ = lx[L.LEFT_ANKLE]; rax, ray, _ = lx[L.RIGHT_ANKLE]

        elbow_off    = min(abs(ex_ - wsx) / 0.30, 1.0)
        release_ang  = angle_three_points(sx,sy, ex_,ey, wsx,wsy) / 180.0
        knee_bend    = angle_three_points(hx,hy, kx,ky, ax,ay) / 180.0
        landing_tilt = min(abs(lay - ray) / 0.10, 1.0)

        return [elbow_off, release_ang, knee_bend, landing_tilt]
    except Exception:
        return None


# ── Main ───────────────────────────────────────────────────────────────────────

def process_video(path: str, mode: str, label: float, sample_every: int,
                  writer: csv.writer, pose) -> int:
    cap = cv2.VideoCapture(path)
    frame_idx = 0
    written = 0
    while cap.isOpened():
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx % sample_every == 0:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            results = pose.process(rgb)
            if results.pose_landmarks:
                feats = extract_squat(results) if mode == "squat" else extract_shot(results)
                if feats is not None:
                    writer.writerow(feats + [label])
                    written += 1
        frame_idx += 1
    cap.release()
    return written


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input_dir",  required=True)
    parser.add_argument("--output_csv", required=True)
    parser.add_argument("--mode",       choices=["squat", "shot"], required=True)
    parser.add_argument("--fps",        type=int, default=10,
                        help="Sample 1 frame every N frames (default 10 = 3fps from 30fps)")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(args.output_csv)), exist_ok=True)
    file_exists = os.path.exists(args.output_csv)

    headers = {
        "squat": ["knee_angle", "trunk_lean", "knee_forward", "hip_balance", "label"],
        "shot":  ["elbow_offset", "release_angle", "knee_bend", "landing_tilt", "label"],
    }[args.mode]

    total = 0
    with open(args.output_csv, "a", newline="") as f:
        writer = csv.writer(f)
        if not file_exists:
            writer.writerow(headers)

        with mp_pose.Pose(static_image_mode=False, min_detection_confidence=0.5) as pose:
            for fname in sorted(os.listdir(args.input_dir)):
                if not fname.lower().endswith((".mp4", ".mov", ".avi")):
                    continue
                name_lower = fname.lower()
                if "good" in name_lower:
                    label = 1.0
                elif "bad" in name_lower:
                    label = 0.0
                else:
                    print(f"  Skipping {fname} — name must contain 'good' or 'bad'")
                    continue

                path = os.path.join(args.input_dir, fname)
                n = process_video(path, args.mode, label, args.fps, writer, pose)
                print(f"  {fname:40s}  label={label:.0f}  rows={n}")
                total += n

    print(f"\nDone. {total} rows appended to {args.output_csv}")


if __name__ == "__main__":
    main()
