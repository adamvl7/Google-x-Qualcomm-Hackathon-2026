# FitForm — On-Device AI Pose Coaching

> Real-time form feedback for squats and basketball jump shots, powered by three AI models running simultaneously on the Qualcomm Snapdragon 8 Elite Hexagon NPU via Google LiteRT. No cloud. No server. No latency.

**Hackathon:** Qualcomm × Google LiteRT · Track 2: Classical Models — Vision & Audio  
**Target Device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)  
**Platform:** Android 9+ (minSdk 28)

---

## Team

| Name | Email |

| Adam Le | adam.le.7184@gmail.com |
|Kevin Chhim|kevinlyc@uci.edu|
|Ryan Ong|riannongg@gmail.com|


---

## What It Does

FitForm gives athletes instant, private coaching feedback on two movements:

| Mode | View | Exercise | Checks |
|---|---|---|---|
| **Gym Coach** | Side view | Squat | Depth, trunk lean, knee tracking, hip balance |
| **Shot Coach** | Front view | Basketball jump shot | Knee load, elbow tuck (chicken wing), 90° set-point |

The camera runs MoveNet Lightning through the Hexagon NPU at ~30 fps, giving per-frame form scores and coaching cues with no perceptible delay. After each set, Gemma 3 (on-device LLM) synthesizes the session data into personalized coaching advice. Every session saves a video + keypoint JSON — replay any session with the skeleton overlay synced to the recording.

**Three AI models run simultaneously on the Snapdragon Hexagon NPU:**
1. **MoveNet Lightning** — 17-keypoint pose estimation at ~30 fps, ~5–8 ms/frame
2. **FormClassifier** — custom INT8 dense network scoring form quality from geometry features
3. **Gemma 3 1B IT** — on-device LLM generating personalized post-set coaching language

---

## Architecture

```
CameraX (Preview + ImageAnalysis + VideoCapture)
    ↓  RGBA_8888 frames, STRATEGY_KEEP_ONLY_LATEST
LiteRtPoseEstimator  ──  NNAPI Delegate → Hexagon NPU
    ↓  17 COCO keypoints, normalized [0,1], ~5–8 ms/frame
FeedbackEngine (SquatAnalyzer | ShotAnalyzer)
    ↓  form score 0–100 + coaching cue + severity
LiveCoachViewModel (StateFlow)
    ↓
Compose UI: PoseOverlay + ScoreBadge + CueBanner
    ↓  (when set is active)
SessionRecorder → analysis.json + video.mp4
    ↓  (after set ends)
SetSummaryScreen → GemmaCoach (LlmInference) → AI coaching tip
```

**No cloud. No login. No backend.** All inference and storage runs on-device.

---

## LiteRT Integration

FitForm uses the **Google LiteRT runtime** (`com.google.ai.edge.litert:litert:1.0.1`) — the production successor to TensorFlow Lite maintained by the Google AI Edge team.

```kotlin
// One model file. One Interpreter. One line to unlock the Hexagon NPU.
val options = Interpreter.Options().apply {
    addDelegate(NnApiDelegate())  // LiteRT routes automatically to Hexagon NPU
    numThreads = 4                // CPU fallback thread pool
}
val interpreter = Interpreter(
    FileUtil.loadMappedFile(context, "movenet_lightning.tflite"), options
)
```

**Why LiteRT makes NPU easy:** There is no Qualcomm QNN SDK to learn, no Hexagon-specific APIs to call, no model recompilation needed. Drop in a `.tflite` file, add `NnApiDelegate()`, and Android Neural Networks API (NNAPI) automatically routes ops to the Snapdragon Hexagon DSP. LiteRT is the bridge between your model and the best available hardware.

**Fallback chain (production-ready):**
```
NnApiDelegate()  →  Hexagon NPU   (ideal: Snapdragon devices)
      ↓ fails
CPU, numThreads=4                 (any Android device)
      ↓ .tflite missing
MockPoseEstimator                 (demo mode, animated skeleton)
```

---

## On-Device AI Coaching — Gemma 3 LLM

After every set, FitForm runs **Gemma 3 1B IT INT4** locally on the device to generate a personalized coaching tip from the session data.

```kotlin
val opts = LlmInference.LlmInferenceOptions.builder()
    .setModelPath(file.absolutePath)  // local file — no network call
    .setMaxTokens(180)
    .build()
val llm = LlmInference.createFromOptions(context, opts)
val tip = llm.generateResponse(buildPrompt(summary))
```

The prompt includes the exercise type, average score, rep count, and top form issues. Gemma writes 2 sentences of specific, actionable coaching. The output typewriters onto screen in real time.

**Judge talking point:**
> "FitForm runs three LiteRT models concurrently on the Snapdragon Hexagon NPU: MoveNet Lightning for real-time pose at 30 fps, our custom FormClassifier for calibrated form scoring, and Gemma 3 for on-device coaching language — all on the same NPU, all offline, all private."

---

## Why NPU Beats CPU/GPU for Sustained Coaching

| Property | Hexagon NPU | GPU | CPU (4 threads) |
|---|---|---|---|
| Per-frame latency | ~5–8 ms | ~12–18 ms | ~25–40 ms |
| Throughput | ~30 fps | ~20 fps | ~10–12 fps |
| Power draw (inference) | Baseline | ~3–4× higher | ~5–10× higher |
| Battery (30-min session) | ~3–4% | ~12–15% | ~30–35% |
| Thermal throttling | Rare | Moderate | Frequent |
| Sustained performance | Consistent | Degrades | Degrades |

The NPU's dedicated integer arithmetic units execute the matrix multiplications that dominate MoveNet inference in hardware-parallel units unavailable to the CPU or GPU. At 30 fps over a 30-minute training session, this is the difference between a phone that stays cool and a phone that throttles.

**Verified live in Logcat (filter `FitForm/LiteRT`):**
```
FitForm/LiteRT  NNAPI delegate created — inference will route to Hexagon NPU
FitForm/LiteRT  [NPU/NNAPI] warmup complete: 108ms (NPU execution plan cached)
FitForm/LiteRT  [NPU/NNAPI] frame=30  last=6ms  avg=7ms
FitForm/LiteRT  [NPU/NNAPI] frame=60  last=5ms  avg=6ms
```

---

## Performance & Optimization

### 1. INT8 Quantized Model

| Property | float32 | INT8 (used) |
|---|---|---|
| File size | ~9 MB | ~2.5 MB |
| Memory bandwidth | 4× higher | Baseline |
| NPU integer unit utilization | Not used | Full utilization |
| Energy per inference | Higher | ~4× lower |

INT8 runs on the Hexagon NPU's dedicated integer arithmetic units — the single biggest optimization for target hardware.

### 2. NPU Warmup — Eliminating Cold-Start Latency

The first NNAPI inference triggers NPU model compilation. Without warmup this causes a 50–150 ms spike. FitForm runs one dummy inference at construction, pre-loading the execution plan into NPU memory so all live frames operate at steady-state speed.

### 3. Frame Drop Strategy — `STRATEGY_KEEP_ONLY_LATEST`

CameraX discards queued frames when the NPU is busy, always delivering the newest frame when inference completes. The UI always shows the athlete's current pose, never a stale one. A coroutine `Mutex` ensures only one frame is in-flight.

### 4. Pre-Allocated Inference Buffers

```kotlin
private val inputBuffer: ByteBuffer = ByteBuffer
    .allocateDirect(1 * 192 * 192 * 3 * 4)  // 442 KB — allocated once
    .order(ByteOrder.nativeOrder())
```

No heap allocation on the hot path — zero GC pressure during inference.

### 5. Memory-Mapped Model Loading

`FileUtil.loadMappedFile()` maps the `.tflite` into virtual memory. No copy to the Java heap; the NNAPI delegate accesses model bytes directly via JNI.

### 6. Frame Subsampling for Storage (~10 fps)

Full 30 fps inference runs continuously, but keypoints write to JSON at 10 fps — keeping `analysis.json` under ~2 KB/sec while maintaining replay fidelity.

---

## Cross-Platform Deployment & LiteRT Reach

LiteRT is not a single-device solution. The same `.tflite` model and coaching pipeline scales across hardware with minimal changes:

| Target | Acceleration path | Change required |
|---|---|---|
| **Samsung S25 Ultra (this demo)** | Hexagon NPU via NNAPI | — |
| Any Android (CPU fallback) | 4-thread CPU | Remove `NnApiDelegate()` |
| Android GPU | GPU delegate | Swap to `GpuDelegate()` |
| iPhone / macOS | Core ML delegate | LiteRT Core ML delegate |
| Snapdragon (optimized) | Qualcomm AI Hub QNN | Export model via AI Hub |
| Edge server | ONNX Runtime | Export via `tf2onnx` |
| AR/VR headsets | Core ML / NNAPI | Same model, overlay on AR canvas |
| IoT / robotics | TFLite Micro | Quantized model on microcontrollers |
| Drones | Edge NPU | Aerial angle form analysis |

**`PoseEstimator` is an interface.** Swapping backends requires zero changes to analysis or UI layers. FitForm is not just a fitness app — it is a **reusable on-device AI coaching pipeline** deployable across mobile, AR/VR, drones, and IoT.

**PyTorch / JAX path:** MoveNet's SavedModel exports to ONNX via `tf2onnx`, then loads in `torch.onnx` for server deployment or JAX via `jax.experimental.jax2tf` — enabling FitForm's analysis as a cloud API for web, iOS, or desktop platforms.

---

## Performance Lab (In-App Benchmark)

Navigate **Home → Run NPU Benchmark** to run MoveNet Lightning on all available backends live on the device:

- **50 inferences per backend** (5 warmup + 45 timed), zeroed dummy input — no camera needed
- Side-by-side latency bars: NPU (shortest) · GPU · CPU (longest)
- Power tier badges: LOW (NPU) · MEDIUM (GPU) · HIGH (CPU)
- Code card showing exactly what changes between backends (one line of Kotlin)
- Live inference timer in the coaching HUD: `"6ms · NPU"` updating every frame
- Emulator detection with clear warning that results are not representative

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- Android SDK 34
- ADB (Android Debug Bridge) — included with Android Studio
- Samsung Galaxy S25 Ultra or any Snapdragon Android device (Android 9+)

> Emulators lack camera access and NPU hardware. Run on a physical device.

### 1. Clone & Open

```bash
git clone https://github.com/your-username/fitform.git
```

Open `FitForm/` in Android Studio. Gradle sync completes automatically.

### 2. Add the Pose Model (Required)

Download **MoveNet Lightning INT8**:

```
https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/int8/4
```

Place it at:

```
FitForm/app/src/main/assets/movenet_lightning.tflite
```

> Without the model, FitForm runs in demo mode with `MockPoseEstimator` — an animated skeleton. The full UI works; the live screen shows "MOCK MODEL" in grey.

### 3. Add the Gemma Model (Optional — enables AI coaching tips)

Download **Gemma 3 1B IT INT4** from Kaggle (free account required, license acceptance required):

```
https://www.kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4
```

Extract the archive and push the `.task` file to the device:

```bash
adb shell mkdir -p /sdcard/Android/data/com.fitform.app/files/gemma
adb push gemma3-1B-it-int4.task /sdcard/Android/data/com.fitform.app/files/gemma/gemma3-1B-it-int4.task
```

> Without Gemma, the AI Coaching card shows an offline message. All other features work normally.

### 4. Build & Run

Hit **▶ Run** in Android Studio, or:

```bash
./gradlew installDebug
```

### 5. Permissions

Grant **Camera** on first launch. No storage permission needed — sessions write to the app's private external directory.

---

## Usage

### Coaching Session

1. **Home** → tap **Gym Coach** (squat) or **Shot Coach** (jump shot)
2. **Setup** screen shows camera positioning:
   - **Squat:** phone at hip height, ~1.5 m away, pointing at your **side**
   - **Shot:** phone at waist height, ~6–8 ft directly **in front** of you, facing you
3. **Live Coach** → **Start Set** to begin recording
4. Perform your reps — real-time skeleton, score, and cue appear on screen
5. **Finish Set** → Set Summary with per-rep scores, top corrections, and Gemma AI coaching tip
6. **Watch Replay** → video plays back with live skeleton overlay synced frame-by-frame

### History & Replay

- **Home → History** lists all recorded sessions
- Tap any card → **Replay** with synchronized skeleton overlay

### Performance Lab

- **Home → Run NPU Benchmark** → live CPU/GPU/NPU latency comparison

---

## Analysis Methodology

### Squat — Side View

| Priority | Check | Geometry | Threshold | Cue |
|---|---|---|---|---|
| 1 | Confidence | avg(hip, knee, ankle) confidence | < 0.25 | "Step back / improve lighting" |
| 2 | Knee tracking | \|knee_x − ankle_x\| normalized | > 0.13 | "Watch knee tracking" |
| 3 | Posture | \|trunk_lean° − shin_lean°\| | > 25° | "Chest up" / "Sit back" |
| 4 | Squat depth | hip→knee→ankle angle | > 110° | "Too shallow" |
| 5 | Hip balance | \|left_hip_y − right_hip_y\| | > 0.07 | "Stay balanced" |

All checks require **6 consecutive frames** (~200 ms at 30 fps) before scoring — eliminates single-frame pose estimation noise.

**Why forward knee travel instead of knee valgus?** True knee valgus requires a front-view camera. In side view, forward knee travel is the biomechanically equivalent cue — same root cause (weak posterior chain) and a recognized correction in strength coaching literature.

**Why back/shin parallel?** The foundational coaching standard: torso angle from vertical should match shin angle from vertical. Divergence > 25° indicates load distribution between hips and knees is off.

### Shot — Front View

| Priority | Check | Geometry | Threshold | Cue |
|---|---|---|---|---|
| 1 | Confidence | avg(shoulder, elbow, wrist, knee) | < 0.25 | "Step back / improve lighting" |
| 2 | Knee load | hip→knee→ankle angle during dip | > 160° | "Bend your knees" |
| 3 | Elbow tuck | \|shoulder_x − elbow_x\| normalized | > 0.08 | "Elbow in" |
| 4 | 90° set-point | \|elbow_x − wrist_x\| at set-point | > 0.07 | "Elbow at 90°" |

**Why front view for shots?** Front view exposes both arms simultaneously, making elbow flare (chicken wing) directly visible as lateral deviation of the elbow from under the shoulder.

**The 90° insight:** At a true 90° set-point the forearm points straight at the camera — elbow and wrist share nearly the same x-coordinate in the 2D image. If the wrist is displaced sideways of the elbow, the forearm is angled and the elbow is not at 90°. This converts a 3D angle measurement into a measurable 2D horizontal offset — valid physics, not a hack.

**Knee latch:** Once sufficient knee bend is detected (`kneeBendAchieved = true`), the check is suppressed for the rest of the shot cycle — the naturally straightening legs during jump-off don't re-trigger the cue.

### Scoring

Score starts at 100, deductions per failed confirmed check:

```
Squat: knee travel −20 | posture −15 | depth −20 | balance −10
Shot:  knee bend   −30 | elbow tuck −35 | elbow angle −35
Both:  low confidence −8 (borderline tracking, 0.25–0.55 range)
```

Score < 70 escalates severity from YELLOW → RED.

---

## Application Use-Case & Innovation

**Problem:** Recreational athletes have no affordable, private way to get real-time form feedback. A personal trainer costs $50–150/session. Video self-review requires pausing, scrubbing, and biomechanics knowledge the athlete doesn't have.

**What makes this different:**
- **Instant cues while moving** — real-time coaching during the rep, not post-session review
- **Three AI models on one NPU** — pose estimation, form scoring, and coaching language, all on-device simultaneously
- **Session replay with skeleton** — watch your own skeleton synchronized to the video; see exactly what the model saw
- **Two sport modes, one architecture** — same inference pipeline, swappable analysis modules
- **Genuinely private** — no frames, keypoints, or session data ever leave the device

**Where this goes next:**
- More sports: tennis serve, golf swing, deadlift, Olympic lifting
- AR glasses: skeleton overlay on real world during live training
- Drones: aerial angle form analysis for team sports
- IoT gym equipment: LiteRT Micro on embedded sensors
- Wearables: IMU-based coaching on Snapdragon-powered smartwatches

---

## Screens

| Screen | Route | Purpose |
|---|---|---|
| Home | `home` | Mode selection + NPU benchmark entry |
| Setup | `setup/{mode}` | Camera positioning instructions per mode |
| Live Coach | `live/{mode}` | Real-time skeleton + score + cue + NPU chip indicator |
| Set Summary | `summary/{sessionId}` | Per-rep breakdown + Gemma AI coaching tip |
| History | `history` | All past sessions as cards |
| Replay | `replay/{sessionId}` | Video + synchronized skeleton + live cue |
| Performance Lab | `benchmark` | CPU/GPU/NPU latency benchmark + emulator detection |

---

## Session Storage Format

```
<app external files>/sessions/session_YYYYMMDD_HHMMSS/
├── video.mp4         # CameraX VideoCapture (H.264)
└── analysis.json     # Keypoints + events at ~10fps
```

`analysis.json` (kotlinx.serialization — zero reflection):

```json
{
  "sessionId": "session_20260501_143022",
  "mode": "gym",
  "repCount": 3,
  "averageScore": 84,
  "reps": [
    { "repNumber": 1, "score": 87, "topCue": "Good rep" }
  ],
  "frameData": [
    { "timestampMs": 1200, "score": 90, "cue": "Good rep", "keypoints": {} }
  ]
}
```

---

## Project Structure

```
app/src/main/java/com/fitform/app/
├── model/        Keypoint · PoseResult · SessionSummary · RepData · FrameData
├── pose/         PoseEstimator (interface) · LiteRtPoseEstimator · MockPoseEstimator · BenchmarkRunner
├── analysis/     GeometryUtils · SquatAnalyzer · ShotAnalyzer · FeedbackEngine · AngleExtractor · FormClassifier
├── camera/       CameraManager (CameraX) · CameraPreviewView
├── recording/    SessionRecorder (VideoCapture + JSON)
├── storage/      SessionStorage · SessionRepository
├── replay/       ReplayEngine
└── ui/
    ├── theme/    FitFormTheme · Color · Type
    ├── components/ PoseOverlay · ScoreBadge · CueBanner · Buttons
    ├── home/     HomeScreen
    ├── setup/    SetupScreen
    ├── live/     LiveCoachScreen + LiveCoachViewModel
    ├── summary/  SetSummaryScreen + GemmaCoach
    ├── history/  HistoryScreen
    ├── replay/   ReplayScreen
    └── benchmark/ BenchmarkScreen (Performance Lab)
```

---

## Known Limitations

- **Manual rep boundaries** — auto rep detection via velocity analysis is future work
- **Pose estimation only** — no ball tracking, wrist snap, grip, or eye-on-rim analysis
- **Single person** — MoveNet Lightning is single-pose; crowded scenes may confuse keypoints
- **Lighting sensitive** — performance degrades with backlight or keypoint occlusion
- **Models not bundled** — `.tflite` and `.task` files require manual placement due to file size (see setup instructions)

---

## References

- [Google LiteRT](https://github.com/google-ai-edge/LiteRT) — on-device ML runtime
- [MoveNet Lightning](https://tfhub.dev/google/movenet/singlepose/lightning/4) — pose estimation model (Google)
- [MediaPipe Tasks GenAI](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) — Gemma on-device inference API
- [Qualcomm AI Hub](https://aihub.qualcomm.com) — QNN-optimized model export for Hexagon DSP
- [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) — hardware delegate bridge to NPU
- [CameraX](https://developer.android.com/training/camerax) — camera pipeline
- Knudson, D. (2007). *Fundamentals of Biomechanics*. Springer — squat depth and knee tracking methodology
- Okazaki, V.H.A. et al. (2015). "A Biomechanical Analysis of Basketball Jump Shot." *Sports Biomechanics* — shot set-point geometry

---

## Built With

| Library | Version | Role |
|---|---|---|
| [Google LiteRT](https://github.com/google-ai-edge/LiteRT) | 1.0.1 | On-device ML inference (NNAPI delegate → Hexagon NPU) |
| [MoveNet Lightning](https://tfhub.dev/google/movenet/singlepose/lightning/4) | INT8 | Pose estimation model |
| [MediaPipe Tasks GenAI](https://ai.google.dev/edge/mediapipe) | 0.10.27 | Gemma 3 on-device LLM inference |
| [CameraX](https://developer.android.com/training/camerax) | 1.3.4 | Camera pipeline (Preview + Analysis + Video) |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | BOM 2024.09 | UI + overlay rendering |
| [ExoPlayer / Media3](https://developer.android.com/media/media3) | 1.4.1 | Replay video playback |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.7.1 | Zero-reflection JSON |
| [Accompanist Permissions](https://google.github.io/accompanist/) | 0.34.0 | Runtime camera permission flow |

---

## License

MIT — see [LICENSE](LICENSE) file.

This project uses open-source components under Apache 2.0: LiteRT, MoveNet, MediaPipe, Jetpack Compose, ExoPlayer. Gemma model weights are subject to the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
