# Repository Audit — 2026-10-07

Scope: branch `claude/project-thread-awovbl`, stacked on PR #1 (`claude/project-thread-5f3v7e` = `ccr-e09c0f54-r81172` + CI).
Method: every module read against its docs; new and changed code compiled and unit-tested on a host JVM (200 tests,
0 failures, see "Verification" below). No Android SDK and no device were available, so nothing here is physical evidence.

State vocabulary (also in code: `com.neuralcamera.models.execution.EvidenceState`):
**PROVEN / NOT_PROVEN** = a physical claim judged by a gate checker on the OnePlus 15. **TESTED / NOT_TESTED** = code
exercised by tests. JVM tests and synthetic benchmarks are development evidence only.

## Proof flags (unchanged)

```
RAW_BURST_PROVEN=FALSE
HTP_INFERENCE_PROVEN=FALSE
CLASSICAL_MERGE_ADVANTAGE_PROVEN=FALSE   (MERGE_ADVANTAGE=NOT_PROVEN)
NEURAL_ISP_PROVEN=FALSE
PRODUCT_ADVANTAGE_PROVEN=FALSE
FULL_PIPELINE_PROVEN=FALSE
```

## A. Complete (code + JVM tests; correct for what it claims, no hardware claim)

| Area | Where |
|---|---|
| Burst pairing by sensor timestamp, ownership/close rules | `camera-core` `BurstCollector` |
| Classical temporal merge (tile align, noise-aware, motion-robust), Bayer wrapper | `neural-isp` `temporal/` |
| DNG reader, colour pipeline (demosaic, matrices, tone), JPEG/DNG encoders | `neural-isp` `dng/`, `color/`, `encode/` |
| Inference contract with attribution, FP32 CPU reference, `denoise-tiny-v1` artifacts | `neural-runtime` |
| Gate 1/2 independent checkers + self-tests | `tools/proof/check_gate{1,2}.py` |
| Failure-aware execution contract (new) | `models` `execution/StageOutcome.kt` |
| Camera 3A contracts (new) | `camera-core` `threea/` (moved from `capture-intelligence`) |
| Capture orchestration state machine + driver (new) | `camera-core` `orchestration/` (moved from `capture-intelligence`) |
| Sensor calibration model + noise-law fit (new) | `neural-isp` `calibration/` |
| RAW10/12/16 unpack, black level, defect, lens shading, stage order (new) | `neural-isp` `raw/` |
| Rolling-shutter, gyro integration, OIS/EIS/crop-aware motion (new) | `capture-intelligence` `motion/` |
| Per-frame quality metrics + ranking (new) | `quality-engine` `frame/` |
| Scene assessment from exposure/histogram/motion (new) | `capture-intelligence` `scene/` |
| Model artifact lifecycle: checksum, stack, rollback, QNN pairing (new) | `neural-runtime` `registry/` |
| Provenance (captured/reconstructed/generated) (new) | `gallery` `provenance/` |
| Atomic, journaled bundle storage + crash recovery (new, now used by the app's media repository) | `gallery` `storage/` |
| Quality policies Instant…Battery Saver (new) | `capture-intelligence` `policy/` |

## B. Code-complete but untested on the device (NOT_TESTED physically)

- App capture path: Camera2 open/preview, YUV burst (`RealCamera2Controller`), merge, JPEG save, MediaStore insert.
  Compiles in CI only; whether it runs correctly on the OnePlus 15 is unknown from the repo.
- Gate 1 recorder (`RawBurstRecorder`, `Gate1ProbeActivity`) and `run_gate1_adb.sh`.
- Gate 2 harness (`OrtBackend` QNN EP, `Gate2ProbeActivity`) and `run_gate2_adb.sh`.
- `DeviceCapabilityResolver`, `LogicalToPhysicalCameraMap`, `StreamMatrixTester`, `SensorTimelineSynchronizer`.
- Every new contract in section A: logic TESTED on the JVM, hardware behaviour NOT_TESTED, and **not yet wired into the
  camera** (see D).

## C. Physically tested

**None with committed evidence.** `profiles/runtime/OnePlus_15/20260925_000000/` is marked UNVERIFIED (no raw logs,
contradictory timestamp). Commits on Oct 6 suggest the app was run by hand, but no log, report or capture is in the repo,
so it cannot be counted.

## D. Missing

| Missing | Notes |
|---|---|
| Camera2 adapters for the new contracts | 3A detector and orchestrator are now wired (`RealCamera2Controller.captureBurst`: precapture + AF trigger, convergence wait, AE/AWB lock, retry, partial, session recovery; device NOT_TESTED). Motion, scene, quality ranking and policies are still not called by `RealCamera2Controller`/`MainActivity`. |
| Real scene inputs | `MainActivity.planFor` uses a fixed 120 lux and fixed motion (labelled placeholders). |
| Exposure bracketing in capture | Controller refuses `bracketSteps` (returns no frames). Planner exists. |
| RAW in the camera UI | RAW exists only in the Gate 1 probe. The app's "original" is the reference frame's **luma as a gray JPEG**, not sensor data. |
| Chromatic aberration, distortion correction | Declared stages, `implemented=false`; requesting them yields a FAILED/SKIP stage. |
| Semantic scene signals (subject, sky, face, text) | Reported as `NOT_AVAILABLE`; needs a detector. |
| Temperature-dependent calibration | Hook only (`TemperatureCompensation.NONE`). |
| HEIF / Ultra HDR encoder, HDR merge | None. |
| Vulkan / XNNPACK backends | None. |
| Gate 3 tooling | 30-scene dataset, frozen baselines, blind A/B tool, metric scripts: none. |
| Provenance in saved files | `ProvenanceRecord` exists but `MainActivity` still writes its own ad-hoc metadata JSON. |
| Directory fsync | Java offers no portable call; documented in `AtomicMediaStore`. |

## E. Overclaimed or unsafe (found, and what changed)

| Finding | Fix in this branch |
|---|---|
| `StandardVideoPipeline` slept 2 ms and reported `neuralEnhanced=true` on every 15th frame with no processing | Never reports enhancement; exposes `isEnhancementKeyframe` (budget only) |
| `ReferenceDatasetManager.verifyRegression` counted unmeasured scenes as **passed** (defaulted to the baseline) | Unmeasured scenes are `notTestedScenes`; `isRegressionFree` needs every scene measured |
| `ZeroCopyAuditor` reported `PASS` for zero-copy from hardcoded records | Reports design expectations only, `actualHardwarePathStatus = NOT_MEASURED` |
| `BaselineImagePipeline` named the merge as applied even when Reality Guard reverted to the original | Pipeline name states the revert/blend |
| Unused UI components defaulted to "QNN HTP NPU" and "RAW" | Defaults now "NONE (no backend verified)" and JPEG |
| Media repository wrote original/master/metadata non-atomically; a failed write could leave a partial bundle | Uses `AtomicMediaStore` (journal + temp + rename, recovery on start); duplicate ids fail instead of overwriting |
| MediaStore save left an `IS_PENDING` row on a failed write | Pending row is deleted on failure |
| QNN: project bundles **QNN 2.42.0** (transitive from `onnxruntime-android-qnn:1.29.0`); device ships **2.37.4** | Recorded as `QnnPairing.MISMATCHED_UNVERIFIED` in `ModelLifecycleRegistry.CURRENT_QNN_STACK`; an unverified accelerator artifact is refused activation outside proof harnesses |
| Still open, documented not fixed: `StandardQualityEvaluator` derives "sharpness" and "noise" from the same global std-dev | Left in place (Reality Guard path depends on it); `quality/frame/` provides real measurements |
| Still open: `docs/CAMERA_PIPELINE.md` §1 and `docs/ARCHITECTURE.md` §1/§7 described target behaviour as current | Status banners and corrected text added |
| Still open: `UniversalCapturePlanner` lux→ISO table is unused by capture (AE runs auto) | Documented; replace with `scene/` + `policy/` when wiring |

## Status summary

| COMPLETE (JVM-tested logic) | UNTESTED (needs the phone) | MISSING | BLOCKED |
|---|---|---|---|
| Section A | Section B | Section D | Gate 1 run (needs OnePlus 15); Gate 2 run (needs OnePlus 15; QNN 2.42.0 vs 2.37.4 pairing unknown); Gate 3 (needs Gate 1 PASS, dataset, baselines, A/B tooling); NEURAL_ISP / PRODUCT / FULL_PIPELINE (need Gates 1–3) |

## Verification performed here

- New and changed pure-Kotlin sources plus the existing `models`, `neural-isp`, `neural-runtime` (ONNX Runtime CPU EP on
  the host), `quality-engine`, `video-engine`, `data-lab` and `gallery` tests were compiled with Kotlin 2.0.21 and run
  on a host JVM: **200 tests, 0 failures**.
- Later roadmap commits on this branch (C1, C5, D1–D3, J1–J4, Q1/Q3/Q4, P1–P3, N1/N2) extend the same host run to
  **275 tests, 0 failures** (also `capture-intelligence` policy/scene, `ui` capability rules and `camera-core` 3A/ring
  tests). Still synthetic or JVM-only: no item above is device evidence.
- Not compiled here: Android-dependent code (`app`, `ui`, `camera-core` Camera2 classes). Edits there (`MainActivity`
  gallery save, two UI default strings) rely on CI.
- PR #1's CI was red when this branch was cut (owned by another thread); this branch's CI result is in the PR.
