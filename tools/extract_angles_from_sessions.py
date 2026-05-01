"""
extract_angles_from_sessions.py
────────────────────────────────
Reads analysis.json files from FitForm sessions (pulled from the Android
device via ADB) and extracts angle features + rep scores into a training CSV.

The session JSON already has per-frame keypoints and per-rep scores, so we
can compute angles in Python with the same geometry as the Android app.

Usage
─────
  # Pull sessions from device first:
  #   adb pull /sdcard/Android/data/com.fitform.app/files/sessions/ ./my_sessions/

  pip install numpy

  python extract_angles_from_sessions.py \
      --sessions_dir ./my_sessions \
      --squat_csv    ./training_data/squat_training.csv \
      --shot_csv     ./training_data/shot_training.csv

Label assignment
────────────────
  Rep score ≥ 80  → 1.0 (good form)
  Rep score < 60  → 0.0 (bad form)
  60 ≤ score < 80 → skipped (ambiguous, keeps training data clean)

Output CSV columns
──────────────────
  Squat: knee_angle, trunk_lean, knee_forward, hip_balance, label
  Shot:  elbow_offset, release_angle, knee_bend, landing_tilt, label
"""

import argparse
import csv
import json
import math
import os
import sys

# ── MoveNet COCO keypoint indices (must match KeypointIndex.kt) ────────────────
KP = {
    "nose": 0, "left_eye": 1, "right_eye": 2, "left_ear": 3, "right_ear": 4,
    "left_shoulder": 5, "right_shoulder": 6,
    "left_elbow": 7, "right_elbow": 8,
    "left_wrist": 9, "right_wrist": 10,
    "left_hip": 11, "right_hip": 12,
    "left_knee": 13, "right_knee": 14,
    "left_ankle": 15, "right_ankle": 16,
}

GOOD_THRESHOLD = 80
BAD_THRESHOLD  = 60


def _kp(kp_map: dict, name: str):
    """Return (x, y, confidence) for a named keypoint from FrameData.keypoints."""
    k = kp_map.get(name) or kp_map.get(str(KP[name]))
    if k is None:
        return 0.0, 0.0, 0.0
    return k.get("x", 0.0), k.get("y", 0.0), k.get("confidence", 0.0)


def angle_three(ax, ay, bx, by, cx, cy) -> float:
    v1 = (ax - bx, ay - by)
    v2 = (cx - bx, cy - by)
    cos_t = (v1[0]*v2[0] + v1[1]*v2[1]) / (
        math.hypot(*v1) * math.hypot(*v2) + 1e-9
    )
    return math.degrees(math.acos(max(-1.0, min(1.0, cos_t))))


def trunk_lean(sx, sy, hx, hy) -> float:
    dx = abs(sx - hx)
    dy = max(abs(sy - hy), 1e-4)
    return math.degrees(math.atan2(dx, dy))


def extract_squat_row(kp_map: dict):
    lsh = _kp(kp_map, "left_shoulder");  rsh = _kp(kp_map, "right_shoulder")
    lhi = _kp(kp_map, "left_hip");       rhi = _kp(kp_map, "right_hip")
    lkn = _kp(kp_map, "left_knee");      rkn = _kp(kp_map, "right_knee")
    lan = _kp(kp_map, "left_ankle");     ran = _kp(kp_map, "right_ankle")

    # Pick better side
    l_conf = lhi[2] + lkn[2] + lan[2]
    r_conf = rhi[2] + rkn[2] + ran[2]
    if l_conf >= r_conf:
        sh, hi, kn, an = lsh, lhi, lkn, lan
    else:
        sh, hi, kn, an = rsh, rhi, rkn, ran

    if min(hi[2], kn[2], an[2]) < 0.25:
        return None

    knee_angle  = angle_three(hi[0], hi[1], kn[0], kn[1], an[0], an[1]) / 180.0
    trunk       = trunk_lean(sh[0], sh[1], hi[0], hi[1]) / 90.0
    knee_fwd    = min(abs(kn[0] - an[0]) / 0.30, 1.0)
    hip_balance = min(abs(lhi[1] - rhi[1]) / 0.10, 1.0)

    return [knee_angle, trunk, knee_fwd, hip_balance]


def extract_shot_row(kp_map: dict):
    lsh = _kp(kp_map, "left_shoulder");  rsh = _kp(kp_map, "right_shoulder")
    lel = _kp(kp_map, "left_elbow");     rel = _kp(kp_map, "right_elbow")
    lwr = _kp(kp_map, "left_wrist");     rwr = _kp(kp_map, "right_wrist")
    lhi = _kp(kp_map, "left_hip");       rhi = _kp(kp_map, "right_hip")
    lkn = _kp(kp_map, "left_knee");      rkn = _kp(kp_map, "right_knee")
    lan = _kp(kp_map, "left_ankle");     ran = _kp(kp_map, "right_ankle")

    left_arm  = lel[2] + lwr[2]
    right_arm = rel[2] + rwr[2]
    if right_arm >= left_arm:
        sh, el, wr, hi, kn, an = rsh, rel, rwr, rhi, rkn, ran
    else:
        sh, el, wr, hi, kn, an = lsh, lel, lwr, lhi, lkn, lan

    elbow_off    = min(abs(el[0] - wr[0]) / 0.30, 1.0)
    release_ang  = angle_three(sh[0], sh[1], el[0], el[1], wr[0], wr[1]) / 180.0
    knee_bend    = angle_three(hi[0], hi[1], kn[0], kn[1], an[0], an[1]) / 180.0
    landing_tilt = min(abs(lan[1] - ran[1]) / 0.10, 1.0)

    return [elbow_off, release_ang, knee_bend, landing_tilt]


def process_session(json_path: str, squat_writer, shot_writer) -> tuple[int, int]:
    with open(json_path, encoding="utf-8") as f:
        data = json.load(f)

    mode = data.get("mode", "")  # "gym" | "shot"
    reps = {r["repNumber"]: r["score"] for r in data.get("reps", [])}
    frames = data.get("frameData", [])

    # Build a timestamp→rep mapping from events
    events = data.get("events", [])
    rep_windows: list[tuple[int, int, int]] = []  # (rep_number, start_ms, end_ms)
    rep_starts: dict[int, int] = {}
    for ev in sorted(events, key=lambda e: e["timestampMs"]):
        # events carry rep number; first appearance = rep start
        r = ev.get("rep", 0)
        if r and r not in rep_starts:
            rep_starts[r] = ev["timestampMs"]

    squat_rows = shot_rows = 0
    extract_fn = extract_squat_row if mode == "gym" else extract_shot_row

    for frame in frames:
        kp_map = frame.get("keypoints", {})
        ts = frame["timestampMs"]

        # Find which rep this frame belongs to
        rep_score = None
        for rep_num, start_ms in rep_starts.items():
            next_starts = [s for n, s in rep_starts.items() if s > start_ms]
            end_ms = min(next_starts) if next_starts else float("inf")
            if start_ms <= ts < end_ms:
                rep_score = reps.get(rep_num)
                break

        if rep_score is None:
            continue
        if rep_score >= GOOD_THRESHOLD:
            label = 1.0
        elif rep_score < BAD_THRESHOLD:
            label = 0.0
        else:
            continue  # skip ambiguous

        row = extract_fn(kp_map)
        if row is None:
            continue

        if mode == "gym":
            squat_writer.writerow(row + [label])
            squat_rows += 1
        else:
            shot_writer.writerow(row + [label])
            shot_rows += 1

    return squat_rows, shot_rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sessions_dir", required=True)
    parser.add_argument("--squat_csv",    default="./training_data/squat_training.csv")
    parser.add_argument("--shot_csv",     default="./training_data/shot_training.csv")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(args.squat_csv)), exist_ok=True)

    squat_headers = ["knee_angle", "trunk_lean", "knee_forward", "hip_balance", "label"]
    shot_headers  = ["elbow_offset", "release_angle", "knee_bend", "landing_tilt", "label"]

    squat_exists = os.path.exists(args.squat_csv)
    shot_exists  = os.path.exists(args.shot_csv)

    total_sq = total_sh = 0

    with (
        open(args.squat_csv, "a", newline="") as sf,
        open(args.shot_csv,  "a", newline="") as shf,
    ):
        sq_w  = csv.writer(sf)
        sh_w  = csv.writer(shf)
        if not squat_exists: sq_w.writerow(squat_headers)
        if not shot_exists:  sh_w.writerow(shot_headers)

        for root, dirs, files in os.walk(args.sessions_dir):
            for fname in files:
                if fname != "analysis.json":
                    continue
                path = os.path.join(root, fname)
                try:
                    sq, sh = process_session(path, sq_w, sh_w)
                    print(f"  {os.path.relpath(path, args.sessions_dir):50s}  squat={sq}  shot={sh}")
                    total_sq += sq
                    total_sh += sh
                except Exception as e:
                    print(f"  ERROR {path}: {e}", file=sys.stderr)

    print(f"\nDone. squat rows={total_sq}  shot rows={total_sh}")
    print(f"      squat → {args.squat_csv}")
    print(f"      shot  → {args.shot_csv}")


if __name__ == "__main__":
    main()
