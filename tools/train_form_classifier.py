"""
train_form_classifier.py
─────────────────────────
Trains a tiny Dense network on angle/geometry features and exports
INT8-quantized TFLite models for both squat and shot.

Usage
─────
  pip install tensorflow numpy pandas scikit-learn

  python train_form_classifier.py \
      --squat_csv  ./training_data/squat_training.csv \
      --shot_csv   ./training_data/shot_training.csv \
      --output_dir ./trained_models

  # Then copy the .tflite files into the Android assets folder:
  #   cp ./trained_models/form_classifier_squat.tflite  \\
  #      ../app/src/main/assets/
  #   cp ./trained_models/form_classifier_shot.tflite   \\
  #      ../app/src/main/assets/

Model
─────
  Input:  float32[1, 4]  (normalized angle features)
  Hidden: Dense(64, ReLU) → Dense(32, ReLU)
  Output: Dense(1, Sigmoid)  → form quality ∈ [0, 1]
  Loss:   MSE (regression against 0.0 / 1.0 labels)

The exported TFLite uses DEFAULT quantization (INT8 weights, float32 I/O)
which runs on the NNAPI Hexagon NPU delegate without any changes to the
Android inference code.
"""

import argparse
import os
import sys

import numpy as np
import pandas as pd

try:
    import tensorflow as tf
    from sklearn.model_selection import train_test_split
    from sklearn.utils import shuffle as sk_shuffle
except ImportError:
    print("ERROR: pip install tensorflow numpy pandas scikit-learn")
    sys.exit(1)


FEATURE_COLS = {
    "squat": ["knee_angle", "trunk_lean", "knee_forward", "hip_balance"],
    "shot":  ["elbow_offset", "release_angle", "knee_bend", "landing_tilt"],
}
OUTPUT_NAMES = {
    "squat": "form_classifier_squat.tflite",
    "shot":  "form_classifier_shot.tflite",
}


def build_model() -> tf.keras.Model:
    model = tf.keras.Sequential([
        tf.keras.layers.Dense(64, activation="relu", input_shape=(4,)),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(1, activation="sigmoid"),
    ])
    model.compile(optimizer="adam", loss="mse", metrics=["mae"])
    return model


def train_and_export(csv_path: str, mode: str, output_dir: str) -> None:
    if not os.path.exists(csv_path):
        print(f"  [{mode}] CSV not found: {csv_path} — skipping")
        return

    df = pd.read_csv(csv_path)
    cols = FEATURE_COLS[mode]
    missing = [c for c in cols + ["label"] if c not in df.columns]
    if missing:
        print(f"  [{mode}] Missing columns: {missing} — skipping")
        return

    X = df[cols].values.astype(np.float32)
    y = df["label"].values.astype(np.float32)
    X, y = sk_shuffle(X, y, random_state=42)

    print(f"\n[{mode}] {len(y)} samples  "
          f"good={int((y >= 0.5).sum())}  bad={int((y < 0.5).sum())}")

    if len(y) < 20:
        print(f"  [{mode}] Too few samples (<20) — collect more data first")
        return

    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.20, random_state=42, stratify=(y >= 0.5).astype(int)
    )

    model = build_model()
    model.summary()

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_loss", patience=15, restore_best_weights=True
        ),
    ]
    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=200,
        batch_size=min(32, len(X_train)),
        callbacks=callbacks,
        verbose=1,
    )

    val_mae = min(history.history["val_mae"])
    print(f"  [{mode}] Best val MAE: {val_mae:.4f}")

    # ── INT8 quantization + TFLite export ──────────────────────────────────────
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Representative dataset for quantization calibration
    def representative_dataset():
        for i in range(min(100, len(X_train))):
            yield [X_train[i:i+1]]

    converter.representative_dataset = representative_dataset

    tflite_model = converter.convert()
    out_path = os.path.join(output_dir, OUTPUT_NAMES[mode])
    with open(out_path, "wb") as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) // 1024
    print(f"  [{mode}] Saved: {out_path} ({size_kb} KB)")
    print(f"  [{mode}] Copy to: app/src/main/assets/{OUTPUT_NAMES[mode]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--squat_csv",  default="./training_data/squat_training.csv")
    parser.add_argument("--shot_csv",   default="./training_data/shot_training.csv")
    parser.add_argument("--output_dir", default="./trained_models")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    train_and_export(args.squat_csv, "squat", args.output_dir)
    train_and_export(args.shot_csv,  "shot",  args.output_dir)

    print("\nDone. Next steps:")
    print("  1. Copy the .tflite files to FitForm/app/src/main/assets/")
    print("  2. Rebuild and reinstall the app")
    print("  3. The FormClassifier will load automatically and blend scores")


if __name__ == "__main__":
    main()
