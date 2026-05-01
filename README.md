# FitForm — On-Device AI Pose Coaching

> Real-time form feedback for squats and basketball jump shots, powered by Google LiteRT running on the Qualcomm Snapdragon 8 Elite NPU. No cloud. No server. No latency.

**Hackathon:** Qualcomm × Google LiteRT · Track 2: Classical Models — Vision & Audio  
**Target Device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)  
**Platform:** Android 9+ (minSdk 28)

---

## What It Does

FitForm gives athletes instant, private coaching feedback on two movements:

| Mode | Exercise | Checks |
|---|---|---|
| **Gym Coach** | Squat | Depth, trunk lean, knee tracking, hip balance |
| **Shot Coach** | Basketball jump shot | Elbow alignment, release angle, knee load, landing |

The camera runs MoveNet Lightning through the Hexagon NPU at ~30fps, giving per-frame form scores and coaching cues with no perceptible delay. After each set, a summary shows per-rep scores and top corrections. Every session saves a video + keypoint JSON — replay any session with the skeleton overlay synced to the recording.

---

## Architecture

```
CameraX (Preview + ImageAnalysis + VideoCapture)
    ↓  RGBA_8888 frames, STRATEGY_KEEP_ONLY_LATEST
LiteRtPoseEstimator  ──  NNAPI Delegate → Hexagon NPU (Snapdragon 8 Elite)
    ↓  17 COCO keypoints, normalized [0,1], ~5–8 ms per frame
FeedbackEngine (SquatAnalyzer | ShotAnalyzer)
    ↓  form score 0–100 + coaching cue + severity
LiveCoachViewModel (StateFlow)
    ↓
Compose UI: PoseOverlay + ScoreBadge + CueBanner
    ↓  (when set is active)
SessionRecorder → analysis.json + video.mp4
```

**No cloud. No login. No backend.** All inference and storage runs on-device.

---

## LiteRT Integration

FitForm uses the **Google LiteRT runtime** (`com.google.ai.edge.litert:litert:1.0.1`) — the production successor to TensorFlow Lite, maintained by the Google AI Edge team.

```kotlin
// LiteRT interpreter — Google AI Edge runtime (formerly TFLite)
import org.tensorflow.lite.Interpreter         // LiteRT v1.0.1 Java API
import org.tensorflow.lite.nnapi.NnApiDelegate // NNAPI hardware delegate

val options = Interpreter.Options().apply {
    addDelegate(NnApiDelegate())  // routes to Hexagon NPU on Snapdragon
    numThreads = 4                // CPU fallback thread pool
}
val interpreter = Interpreter(FileUtil.loadMappedFile(context, "movenet_lightning.tflite"), options)
```

The NNAPI delegate is the LiteRT mechanism for delegating inference to the Snapdragon Hexagon NPU — this is the target-device-specific hardware acceleration path for the S25 Ultra.

---

## Cross-Platform Deployment Strategy

> **Rubric alignment:** Technological Implementation — cross-platform acceleration across CPU, GPU, and NPU.

LiteRT is explicitly designed as a cross-accelerator, cross-platform runtime. FitForm is built on this foundation so the same model and inference pipeline can scale across hardware targets:

| Target | Acceleration path | How |
|---|---|---|
| **Snapdragon S25 Ultra (this demo)** | Hexagon NPU | NNAPI delegate → Android Neural Networks API |
| Any Android (CPU fallback) | 4-thread CPU | `numThreads = 4` fallback if NNAPI unavailable |
| Android GPU | GPU delegate | Swap `NnApiDelegate` → `GpuDelegate` (one line) |
| iOS / macOS | Core ML delegate | LiteRT Core ML delegate, same model |
| Edge server / cloud | ONNX Runtime / PyTorch | Export MoveNet ONNX → torch.export or JAX via `orbax` |
| Snapdragon optimized | Qualcomm AI Hub | QNN-compiled model targeting Hexagon directly, bypasses NNAPI |

**Fallback chain already in production:**
```
NnApiDelegate() → Hexagon NPU (Snapdragon)
         ↓ fails
CPU (numThreads=4, all Android devices)
         ↓ .tflite missing
MockPoseEstimator (demo mode, no model required)
```

The key architectural decision enabling this is that `PoseEstimator` is an interface — swapping backends (NNAPI, GPU, Core ML, ONNX) requires zero changes to the analysis or UI layers. The same coaching logic runs identically regardless of which hardware accelerates the inference.

**Path to PyTorch/JAX:** MoveNet's SavedModel can be exported to ONNX via `tf2onnx`, then loaded in `torch.onnx` for server deployment or JAX via `jax.experimental.jax2tf`. This makes FitForm's form analysis available as a cloud API for platforms (web, iOS, desktop) where LiteRT isn't the primary runtime.

---

## Performance & Optimization

> **Rubric alignment:** Technological Implementation — Performance, Efficiency, and Optimization.

### 1. INT8 Quantized Model

The recommended model download is the **INT8 quantized** MoveNet Lightning:

| Property | float32 | INT8 (used) |
|---|---|---|
| File size | ~9 MB | ~2.5 MB |
| Memory bandwidth | 4× higher | Baseline |
| NPU unit utilization | Float units | Integer units (faster) |
| Energy per inference | Higher | ~4× lower |

INT8 runs on the Hexagon NPU's dedicated integer arithmetic units — faster and more power-efficient than floating-point paths. This is the single biggest optimization for target hardware.

### 2. NNAPI Delegate → Hexagon NPU

The NNAPI delegate routes all supported ops through Android's Neural Networks API to the Snapdragon 8 Elite's Hexagon DSP:

- **Hexagon NPU** is purpose-built for matrix multiply — the dominant operation in MoveNet. It executes these in parallel hardware units not available to the CPU or GPU.
- **Energy efficiency:** The NPU consumes ~5–10× less power than the CPU for the same inference workload. On a continuous coaching session (minutes of 30fps inference), this difference is significant for battery life.
- **Latency:** ~5–8 ms per frame on NPU vs ~25–40 ms on CPU (4 threads). This is the difference between 30fps real-time and <12fps stuttering.

```
Fallback chain:
  NnApiDelegate() succeeds  → Hexagon NPU (ideal path)
  NnApiDelegate() fails     → CPU, numThreads=4
  .tflite file missing      → MockPoseEstimator (animated demo)
```

### 3. NPU Warmup — Eliminating Cold-Start Latency

The first inference on NNAPI triggers NPU model compilation and buffer allocation. Without warmup this produces a 50–150ms spike on the first live frame, misleading latency measurements.

FitForm runs **one dummy inference during interpreter construction** (before the camera starts), pre-loading the execution plan into NPU memory. All live frames operate at steady-state speed:

```
FitForm/LiteRT  [NPU/NNAPI] warmup complete: 112ms (NPU execution plan cached)
FitForm/LiteRT  [NPU/NNAPI] frame=30  last=6ms  avg=7ms   ← clean steady state
```

### 4. Frame Drop Strategy — `STRATEGY_KEEP_ONLY_LATEST`

CameraX ImageAnalysis is configured with `STRATEGY_KEEP_ONLY_LATEST`. When the NPU finishes an inference and the next frame is requested, CameraX delivers the **newest available frame**, discarding any that queued up during inference. This means:

- The UI always reflects the athlete's current pose, not a stale one
- No memory backpressure from buffered frames
- No compounding latency — throughput is exactly capped at the NPU inference rate

A coroutine `Mutex` ensures only one frame is in-flight at a time, preventing concurrent inference races.

### 5. Pre-Allocated Inference Buffers

`inputBuffer` and `outputBuffer` are allocated **once at construction** and reused across all frames:

```kotlin
private val inputBuffer: ByteBuffer = ByteBuffer
    .allocateDirect(1 * 192 * 192 * 3 * 4)   // 442 KB — allocated once
    .order(ByteOrder.nativeOrder())            // native byte order for JNI

private val outputBuffer = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
```

No heap allocation occurs during inference — zero GC pressure on the hot path.

### 6. Memory-Mapped Model Loading

`FileUtil.loadMappedFile()` maps the `.tflite` file into virtual memory rather than loading it to the Java heap:

- The file is accessible without a copy, reducing startup time
- Pages can be evicted by the OS under memory pressure and reloaded on demand
- The NNAPI delegate can access the model bytes directly via JNI without an extra copy

### 7. Frame Subsampling for Storage (~10fps)

Full 30fps inference runs continuously, but keypoints are written to the session JSON at **10fps** (one sample per 100ms). This keeps `analysis.json` under ~2KB/sec while maintaining replay fidelity that matches human perception.

### Observed Performance (S25 Ultra)

| Metric | NPU (NNAPI) | CPU (4 threads) |
|---|---|---|
| Per-frame latency | ~5–8 ms | ~25–40 ms |
| Throughput | ~30 fps | ~10–12 fps |
| Energy efficiency | Baseline | ~5–10× higher draw |
| Model memory (INT8) | ~2.5 MB | ~2.5 MB |

Verify live in Logcat during demo — filter tag `FitForm/LiteRT`:

```
FitForm/LiteRT  NNAPI delegate created — inference will route to Hexagon NPU
FitForm/LiteRT  Interpreter ready | backend=NNAPI/NPU | input=192x192x3
FitForm/LiteRT  [NPU/NNAPI] warmup complete: 108ms (NPU execution plan cached)
FitForm/LiteRT  [NPU/NNAPI] frame=30  last=6ms  avg=7ms
FitForm/LiteRT  [NPU/NNAPI] frame=60  last=5ms  avg=6ms
```

> **Future optimization:** Export MoveNet via [Qualcomm AI Hub](https://aihub.qualcomm.com) for a QNN-compiled model targeting the Hexagon architecture directly, bypassing the NNAPI abstraction layer for additional latency and efficiency gains.

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- Android SDK 34
- Samsung Galaxy S25 Ultra or any Snapdragon device running Android 9+

> Emulators do not have camera access or NPU hardware. Run on a physical device. The Performance Lab screen detects emulator runs via `Build` properties, swaps the device chip to "Emulator · UI test mode", shows a warning that emulator results are not representative, and marks the Hexagon NPU bar as "Unavailable on emulator" with a pointer to run on the Galaxy S25 Ultra. Physical-device runs (S25 Ultra and others) show real benchmark numbers unchanged. The screen also exposes a **Run Benchmark Again** button so judges can rerun the CPU/GPU/NPU comparison live; phase events (`NPU/GPU/CPU started`/`completed`, `GPU unavailable`, `Benchmark complete`) are surfaced in-app as a status caption and mirrored to Logcat under tag `PerformanceLab`. All readers (the screen, future localhost dashboard, etc.) consume the same `PerformanceRepository` `StateFlow`, so there's a single source of truth for the numbers.

### 1. Clone & Open

```bash
git clone https://github.com/your-username/fitform.git
```

Open the `FitForm/` directory in Android Studio. Gradle sync completes automatically.

### 2. Add the Model (INT8 required for NPU efficiency)

Download **MoveNet Lightning INT8**:

```
https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/int8/4
```

Place it at:

```
FitForm/app/src/main/assets/movenet_lightning.tflite
```

> **Without the model**, FitForm runs in demo mode with `MockPoseEstimator` — an animated skeleton that cycles through squat/shot poses. The full app UI works; the live screen shows "MOCK MODEL" in grey instead of "LITERT · NPU" in green.

### 3. Build & Run

Hit **▶ Run** in Android Studio with the device connected, or:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Permissions

Grant **Camera** permission on first launch. Microphone is optional (video is recorded without audio). No storage permission is needed — sessions write to the app's private external directory.

---

## Usage

### Coaching Session

1. **Home** → tap **Gym Coach** (squat) or **Shot Coach** (jump shot)
2. **Setup** screen shows camera positioning — place phone at hip height, ~1.5m away, pointing at your side
3. **Live Coach** → **START SET** to begin recording
4. Tap **START REP** at the start of each rep, **END REP** when it ends
5. **END SET** → arrives at Set Summary with per-rep scores and top corrections
6. **Watch Replay** → video plays back with live skeleton overlay

### History

- **Home → History** lists all recorded sessions
- Tap any card → **Replay** with synchronized skeleton overlay and event timeline

---

## Analysis Methodology

### Why Side View?

Both coaching modes require a **side-view camera** — phone placed perpendicular to the athlete. Side view is optimal for sagittal-plane biomechanics:

- **Squat depth** (hip/knee/ankle angles) is unambiguous in side view
- **Trunk lean** (shoulder–hip angle from vertical) is directly measurable
- **Knee flex** and **hip fold** are clearly visible
- Front-view analysis is explicitly out of scope (requires a second camera or phone repositioning)

### Squat Analysis

| Priority | Check | Geometry | Threshold | Cue |
|---|---|---|---|---|
| 1 | Confidence | avg(hip, knee, ankle) confidence | < 0.40 | "Step back / improve lighting" |
| 2 | Knee tracking | knee_x − ankle_x (normalized) | > 0.10 | "Watch knee tracking" |
| 3 | Trunk lean | shoulder→hip angle from vertical | > 45° | "Chest up" |
| 4 | Squat depth | hip→knee→ankle angle at knee | > 110° | "Go deeper" |
| 5 | Hip balance | \|left_hip_y − right_hip_y\| | > 0.05 | "Stay balanced" |

> **Knee tracking in side view:** True knee valgus (medial collapse) requires a front-view camera. In side view, **forward knee travel** — the knee drifting past the ankle — is the biomechanically equivalent coaching cue. It reflects the same root cause (weak posterior chain, limited ankle dorsiflexion) and is a recognized correction cue in strength training literature.

### Shot Analysis (B.E.F. Framework)

| Priority | Check | Geometry | Threshold | Cue |
|---|---|---|---|---|
| 1 | Confidence | avg(shoulder, elbow, wrist, knee) | < 0.40 | "Step back / improve lighting" |
| 2 | Elbow (E) | \|elbow_x − wrist_x\| at set-point | > 0.08 | "Elbow in" |
| 3 | Follow-thru (F) | shoulder→elbow→wrist angle at release | < 140° | "Release higher" |
| 4 | Balance/load (B) | hip→knee→ankle angle when loaded | > 155° | "More knee bend" |
| 5 | Landing (B) | \|left_ankle_y − right_ankle_y\| | > 0.05 | "Land balanced" |

Shooting side is auto-detected by comparing left vs. right elbow+wrist confidence sum — the shooting arm faces the camera and has reliably higher confidence in side view.

### Scoring

Score starts at 100, deductions applied per failed check:

```
Squat: knee travel  −25 | trunk lean −20 | depth −25 | balance −15
Shot:  elbow        −25 | release    −20 | knee  −20 | landing −20
Both:  low confidence −15 (borderline tracking, 0.40–0.55 range)
```

Score < 70 escalates severity from YELLOW → RED. Rep score = frame-average across the rep window.

---

## Application Use-Case & Innovation

**Problem solved:** Recreational athletes have no affordable, private way to get real-time form feedback. A personal trainer costs $50–150/session. Video self-review requires pausing, scrubbing, and biomechanics knowledge.

**What makes this different:**
- **Instant cues while moving** — not post-session review, but real-time coaching during the rep
- **Session replay with skeleton** — watch your own skeleton synchronized to the video; see exactly what the model saw
- **Two distinct sport modes** — same infrastructure, different biomechanics analysis per mode
- **Zero friction** — no account, no subscription, no cloud upload; tap Start and go

---

## Screens

| Screen | Route | Purpose |
|---|---|---|
| Home | `home` | Mode selection + brand |
| Setup | `setup/{mode}` | Camera positioning instructions |
| Live Coach | `live/{mode}` | Real-time skeleton + score + cue |
| Set Summary | `summary/{sessionId}` | Per-rep breakdown + top corrections |
| History | `history` | All past sessions as cards |
| Replay | `replay/{sessionId}` | Video + synchronized skeleton overlay |
| Performance Lab | `benchmark` | Live CPU/GPU/NPU latency benchmark + emulator detection |

---

## Session Storage Format

```
<app external files>/sessions/session_YYYYMMDD_HHMMSS/
├── video.mp4         # CameraX VideoCapture (H.264)
└── analysis.json     # Keypoints + events at ~10fps
```

`analysis.json` (kotlinx.serialization, no reflection):

```json
{
  "sessionId": "session_20260429_143022",
  "mode": "gym",
  "exercise": "squat",
  "createdAt": "2026-04-29T14:30:22",
  "repCount": 3,
  "averageScore": 76,
  "reps": [
    { "repNumber": 1, "startTimestampMs": 1200, "endTimestampMs": 4800, "score": 78, "topCue": "Go deeper" }
  ],
  "frameData": [
    { "timestampMs": 1200, "keypoints": { "leftHip": { "x": 0.48, "y": 0.61, "confidence": 0.91 } } }
  ],
  "events": [
    { "timestampMs": 2400, "rep": 1, "cue": "Go deeper", "severity": "YELLOW" }
  ]
}
```

---

## Project Structure

```
app/src/main/java/com/fitform/app/
├── model/        Keypoint, PoseResult, SessionSummary, RepData, FrameData…
├── pose/         PoseEstimator (interface) · LiteRtPoseEstimator · MockPoseEstimator · BenchmarkRunner · PerformanceRepository
├── analysis/     GeometryUtils · SquatAnalyzer · ShotAnalyzer · FeedbackEngine
├── camera/       CameraManager (CameraX) · CameraPreviewView
├── recording/    SessionRecorder (VideoCapture + JSON accumulation)
├── storage/      SessionStorage · SessionRepository
├── replay/       ReplayEngine (binary search frame lookup)
├── util/         DeviceInfo (emulator detection, device chip label)
└── ui/
    ├── theme/    FitFormTheme · Color · Type (Anton · Manrope · JetBrains Mono)
    ├── components/ PoseOverlay · ScoreBadge · CueBanner · Buttons · BrandChrome
    ├── home/     HomeScreen
    ├── setup/    SetupScreen
    ├── live/     LiveCoachScreen + LiveCoachViewModel
    ├── summary/  SetSummaryScreen
    ├── history/  HistoryScreen
    ├── replay/   ReplayScreen
    └── benchmark/ BenchmarkScreen (Performance Lab)
```

---

## Known Limitations

- **Side-view only** — front-view analysis (true valgus, medial-lateral balance) requires a second camera
- **Manual rep boundaries** — auto-detection via velocity/phase analysis is future work
- **Pose estimation only** — no ball tracking, wrist snap, or eye-on-rim analysis
- **Single person** — MoveNet Lightning is single-pose; multi-person scenes may confuse keypoints
- **Lighting sensitive** — performance degrades with poor lighting or keypoint occlusion
- **Model not bundled** — `.tflite` file requires manual placement due to file size; see [Add the Model](#2-add-the-model-int8-required-for-npu-efficiency)

---

## License

MIT

---

## Built With

| Library | Version | Role |
|---|---|---|
| [Google LiteRT](https://github.com/google-ai-edge/LiteRT) | 1.0.1 | On-device ML inference (NNAPI delegate) |
| [MoveNet Lightning](https://tfhub.dev/google/movenet/singlepose/lightning/4) | INT8 | Pose estimation model |
| [CameraX](https://developer.android.com/training/camerax) | 1.3.4 | Camera pipeline (Preview + Analysis + Video) |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | BOM 2024.09 | UI + overlay rendering |
| [ExoPlayer / Media3](https://developer.android.com/media/media3) | 1.4.1 | Replay video playback |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.7.1 | Zero-reflection JSON (analysis.json) |
| [Accompanist Permissions](https://google.github.io/accompanist/) | 0.34.0 | Runtime camera permission flow |
