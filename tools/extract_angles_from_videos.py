"""
extract_angles_from_videos.py
─────────────────────────────
Extracts biomechanical angle features from exercise videos using MediaPipe
Tasks API (0.10.x+) and appends labeled rows to a CSV training file.

Usage
─────
  pip install mediapipe opencv-python numpy

  # Put videos in a folder, named with 'good' or 'bad' in the filename:
  #   squat_good_*.mp4   → label 1.0 (good form)
  #   squat_bad_*.mp4    → label 0.0 (bad form)

  python extract_angles_from_videos.py \
      --input_dir  ./videos/squat \
      --output_csv ./training_data/squat_training.csv \
      --mode       squat

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
import urllib.request

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision as mp_vision

# ── Model download ─────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, "pose_landmarker_lite.task")
MODEL_URL  = (
    "https://storage.googleapis.com/mediapipe-models/"
    "pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
)

def ensure_model():
    if not os.path.exists(MODEL_PATH):
        print(f"Downloading pose landmarker model to {MODEL_PATH} …")
        urllib.request.urlretrieve(MODEL_URL, MODEL_PATH)
        print("Download complete.")

# ── Landmark indices (MediaPipe 33-point body model) ──────────────────────────
# https://developers.google.com/mediapipe/solutions/vision/pose_landmarker

IDX = {
    "nose": 0,
    "left_shoulder": 11,  "right_shoulder": 12,
    "left_elbow":    13,  "right_elbow":    14,
    "left_wrist":    15,  "right_wrist":    16,
    "left_hip":      23,  "right_hip":      24,
    "left_knee":     25,  "right_knee":     26,
    "left_ankle":    27,  "right_ankle":    28,
}

# ── Geometry helpers ───────────────────────────────────────────────────────────

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


def lm(landmarks, name):
    """Return (x, y, visibility) for a named landmark."""
    l = landmarks[IDX[name]]
    return l.x, l.y, getattr(l, "visibility", 1.0)


# ── Feature extraction ─────────────────────────────────────────────────────────

def extract_squat(landmarks) -> list | None:
    """Returns [knee_angle/180, trunk_lean/90, knee_forward/0.3, hip_balance/0.1]."""
    try:
        l_hip_v  = lm(landmarks, "left_hip")[2]
        r_hip_v  = lm(landmarks, "right_hip")[2]
        l_knee_v = lm(landmarks, "left_knee")[2]
        r_knee_v = lm(landmarks, "right_knee")[2]
        l_ank_v  = lm(landmarks, "left_ankle")[2]
        r_ank_v  = lm(landmarks, "right_ankle")[2]

        left_conf  = l_hip_v  + l_knee_v + l_ank_v
        right_conf = r_hip_v + r_knee_v + r_ank_v

        if left_conf >= right_conf:
            sx, sy, _ = lm(landmarks, "left_shoulder")
            hx, hy, _ = lm(landmarks, "left_hip")
            kx, ky, _ = lm(landmarks, "left_knee")
            ax, ay, _ = lm(landmarks, "left_ankle")
        else:
            sx, sy, _ = lm(landmarks, "right_shoulder")
            hx, hy, _ = lm(landmarks, "right_hip")
            kx, ky, _ = lm(landmarks, "right_knee")
            ax, ay, _ = lm(landmarks, "right_ankle")

        _, lhy, _ = lm(landmarks, "left_hip")
        _, rhy, _ = lm(landmarks, "right_hip")

        knee_angle  = angle_three_points(hx, hy, kx, ky, ax, ay) / 180.0
        trunk       = trunk_lean(sx, sy, hx, hy) / 90.0
        knee_fwd    = min(abs(kx - ax) / 0.30, 1.0)
        hip_balance = min(abs(lhy - rhy) / 0.10, 1.0)

        return [knee_angle, trunk, knee_fwd, hip_balance]
    except Exception:
        return None


def extract_shot(landmarks) -> list | None:
    """Returns [elbow_offset/0.3, release_angle/180, knee_bend/180, landing_tilt/0.1]."""
    try:
        l_el_v = lm(landmarks, "left_elbow")[2]
        r_el_v = lm(landmarks, "right_elbow")[2]
        l_wr_v = lm(landmarks, "left_wrist")[2]
        r_wr_v = lm(landmarks, "right_wrist")[2]

        if r_el_v + r_wr_v >= l_el_v + l_wr_v:
            sx, sy, _ = lm(landmarks, "right_shoulder")
            ex, ey, _ = lm(landmarks, "right_elbow")
            wx, wy, _ = lm(landmarks, "right_wrist")
            hx, hy, _ = lm(landmarks, "right_hip")
            kx, ky, _ = lm(landmarks, "right_knee")
            ax, ay, _ = lm(landmarks, "right_ankle")
        else:
            sx, sy, _ = lm(landmarks, "left_shoulder")
            ex, ey, _ = lm(landmarks, "left_elbow")
            wx, wy, _ = lm(landmarks, "left_wrist")
            hx, hy, _ = lm(landmarks, "left_hip")
            kx, ky, _ = lm(landmarks, "left_knee")
            ax, ay, _ = lm(landmarks, "left_ankle")

        _, lay, _ = lm(landmarks, "left_ankle")
        _, ray, _ = lm(landmarks, "right_ankle")

        elbow_off    = min(abs(ex - wx) / 0.30, 1.0)
        release_ang  = angle_three_points(sx, sy, ex, ey, wx, wy) / 180.0
        knee_bend    = angle_three_points(hx, hy, kx, ky, ax, ay) / 180.0
        landing_tilt = min(abs(lay - ray) / 0.10, 1.0)

        return [elbow_off, release_ang, knee_bend, landing_tilt]
    except Exception:
        return None


# ── Main ───────────────────────────────────────────────────────────────────────

def process_video(path: str, mode: str, label: float, sample_every: int,
                  writer: csv.writer, landmarker) -> int:
    cap = cv2.VideoCapture(path)
    frame_idx = 0
    written = 0
    while cap.isOpened():
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx % sample_every == 0:
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            result = landmarker.detect(mp_image)
            if result.pose_landmarks:
                lms = result.pose_landmarks[0]
                feats = extract_squat(lms) if mode == "squat" else extract_shot(lms)
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
                        help="Sample 1 frame every N frames (default 10 ≈ 3fps from 30fps video)")
    args = parser.parse_args()

    ensure_model()

    os.makedirs(os.path.dirname(os.path.abspath(args.output_csv)), exist_ok=True)
    file_exists = os.path.exists(args.output_csv)

    headers = {
        "squat": ["knee_angle", "trunk_lean", "knee_forward", "hip_balance", "label"],
        "shot":  ["elbow_offset", "release_angle", "knee_bend", "landing_tilt", "label"],
    }[args.mode]

    base_options = mp_python.BaseOptions(model_asset_path=MODEL_PATH)
    options = mp_vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=mp_vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    total = 0
    with open(args.output_csv, "a", newline="") as f:
        writer = csv.writer(f)
        if not file_exists:
            writer.writerow(headers)

        with mp_vision.PoseLandmarker.create_from_options(options) as landmarker:
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
                n = process_video(path, args.mode, label, args.fps, writer, landmarker)
                print(f"  {fname:50s}  label={label:.0f}  rows={n}")
                total += n

    print(f"\nDone. {total} rows appended to {args.output_csv}")


if __name__ == "__main__":
    main()
