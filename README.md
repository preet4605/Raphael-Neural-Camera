# Raphael Neural Camera

A device-adaptive computational photography project and Camera2 engine targeting modern Android flagships (first target: OnePlus 15, Snapdragon 8 Elite Gen 5, Android 16 / API 36).

The design treats capture as one runtime graph: multi-frame acquisition, IMU timeline alignment, temporal reconstruction, quality assessment and hardware-aware scheduling, with neural restoration (QNN/HTP, Vulkan, CPU) added only after each backend is proven on the device.

**This repository is an early scaffold.** The design above is the goal, not the current state. Read the status below before relying on any claim in the docs.

---

## 📍 Project Status

| Phase | State |
|---|---|
| 0 — Foundation | Complete |
| 1 — Hardware discovery / device profile | Code complete. The committed OnePlus 15 data under `profiles/runtime/` is **UNVERIFIED** (no raw audit logs committed). |
| 2 — Neural runtime foundation | **IN PROGRESS.** Implemented: backend attribution contract, FP32 CPU reference backend, ONNX Runtime backend (CPU EP and QNN EP on the Hexagon HTP), the Gate 2 harness and an independent evidence checker. **Not yet done: any on-device run.** |
| 3 — Computational photography | **Started: classical temporal merge only.** Tile alignment, motion-robust noise-aware merge, a RAW Bayer wrapper, a RAW front end (unpack, black level, defects, lens shading), a baseline colour pipeline and an offline DNG tool pass synthetic-data tests. They have never processed a real RAW capture, and Gate 3 has not started. Neural ISP work waits for the runtime gate. |
| Foundations (3A, orchestration, calibration, motion, quality, scene, failure-aware execution, model lifecycle, provenance, atomic storage, quality policies) | **Contracts + JVM tests only**, not wired into the camera. See [`docs/AUDIT.md`](docs/AUDIT.md). |

Earlier commit messages that call Phase 2 complete were wrong at the time. The Phase 2 code now exists, but it has never run on the device, so no gate is proven.

**Proof flags** (definitions in [`docs/PROOF_GATES.md`](docs/PROOF_GATES.md)); all stay `FALSE` until evidence exists:

```
RAW_BURST_PROVEN=FALSE
HTP_INFERENCE_PROVEN=FALSE
CLASSICAL_MERGE_ADVANTAGE_PROVEN=FALSE
NEURAL_ISP_PROVEN=FALSE
PRODUCT_ADVANTAGE_PROVEN=FALSE
FULL_PIPELINE_PROVEN=FALSE
```

**What is not implemented today**
- Burst capture in the camera UI is camera-processed YUV. Before the burst it runs AE precapture and AF trigger, waits for 3A convergence and locks AE/AWB; partial or unconverged captures are reported and recorded in the saved metadata. None of this has been validated on the device. A separate debug-only Gate 1 recorder (`RawBurstRecorder`) can capture a full-resolution RAW burst as DNG files, but it has never run on the device.
- Neural inference in the camera: nothing uses it. A CPU reference backend and an ONNX Runtime backend (CPU EP, QNN EP/HTP) exist for one validation network (`denoise-tiny-v1`); no on-device run has happened, so HTP execution is unproven. The rest of the model catalog is placeholders, all `UNVERIFIED` with no measured numbers. `StandardInferenceRuntime` throws `BackendUnavailableException` rather than returning unprocessed input.
- Image pipeline: `BaselineImagePipeline` merges 8-bit luma with tile alignment and a motion-robust, noise-aware merge (synthetic-data validated only); chroma is merged along the luma motion (reference chroma when the Reality Guard reverts the merge), grayscale when a frame has no chroma planes. A RAW colour path (demosaic, colour matrix, tone curve) exists in `color/` but is not wired to capture; no HDR rendering yet.
- Encoding: pure-Kotlin JPEG and DNG encoders exist (`:neural-isp` `encode/`, validated with libraw, Pillow and ImageIO on synthetic data). The app saves JPEGs (colour from the reference frame's chroma when present, with luma merged); a DNG needs RAW frames (Gate 1), and there is no HEIF/Ultra HDR encoder.
- Reality Guard: a heuristic comparison of reconstructed vs. original luma. It is not hallucination detection and not cryptographic provenance.
- Zero-copy, IMU sync accuracy, thermal behavior and latency: none measured. The app does read the system thermal status, battery level and power-save mode; a SEVERE thermal status or power saving caps the burst size, and the decision is saved with each photo (untested on the device).

---

## 🏛️ Architecture & Module Topology

The project is structured into 13 cleanly separated modules enforcing a strict one-directional dependency graph:

```
UI (:ui)
 ↓
Application Orchestration (:app)
 ↓
Camera / Capture (:camera-core, :capture-intelligence)
 ↓
Processing / Runtime (:neural-runtime, :neural-isp, :quality-engine, :video-engine)
 ↓
Output (:gallery)
```

### Module Overview

| Module | Purpose |
|---|---|
| `:app` | Application entrypoint, dependency orchestration, and camera permission handling. |
| `:camera-core` | Low-level Camera2 controller, session planner, bounded ring buffers, and sensor timeline synchronizer. |
| `:capture-intelligence` | Scene metering, exposure bracket planning, and motion/jitter analysis. |
| `:neural-runtime` | Model registry, scheduler, thermal/memory budgeting, the inference-runtime contract, the FP32 CPU reference backend, an ONNX Runtime backend (CPU/QNN) and the Gate 2 proof harness. No Vulkan backend; no on-device run yet. |
| `:neural-isp` | Classical temporal merge (tile alignment, motion-robust noise-aware merge, RAW Bayer wrapper), a DNG reader and a luma-only baseline pipeline. Neural demosaic, denoise and HDR fusion are planned. |
| `:quality-engine` | Heuristic quality scoring and the Reality Guard divergence check (not hallucination detection or provenance). |
| `:video-engine` | Video pipeline contracts and a baseline implementation; not validated on device. |
| `:device-profiles` | Hardware capability matrices, profile exporters, and fallback configurations. |
| `:models` | Model descriptors (placeholders, all UNVERIFIED) and manifest validation. |
| `:benchmarks` | Latency, throughput, and memory profiling telemetry. |
| `:gallery` | Non-destructive RAW/Master storage and metadata repository. |
| `:ui` | Jetpack Compose photographic viewfinder, controls, and telemetry HUD. |
| `:data-lab` | Diagnostic tools and dataset collection utilities. |

---

## 🚀 Design Goals and Current State

- **Direct Camera2 architecture**: camera open, preview session and a still `ImageReader` exist. Manual ISO/shutter/focus controls and burst capture do not.
- **Bounded frame ring buffers**: a byte/capacity-bounded repository with unit tests exists. Zero-copy is unverified: no `AHardwareBuffer`/Vulkan/QNN path has been measured.
- **Sensor timeline synchronization**: an interpolating IMU/capture-timestamp synchronizer exists. Its drift and accuracy have not been measured.
- **Device profiles**: a data model, runtime discovery and a hardcoded OnePlus 15 profile. Profile values are unverified, and accelerator backends are marked not usable until proven on the device.
- **Reality Guard**: heuristic divergence check only (see status above).
- **Jetpack Compose UI**: viewfinder, mode rail and diagnostics sheet. Telemetry fields read `N/A` until a real measurement fills them.

---

## 🛠️ Verification & Build

### Prerequisites
- JDK 17 or higher
- Android SDK 35+ (Targeting Android 16 / API 36)
- Gradle 8.11+

### Running Architecture Checks & Unit Tests
```bash
# Verify architectural boundaries and module conventions
./tools/verify_architecture.sh

# Run comprehensive unit test suite across all 13 modules
./tools/run_all_tests.sh
```

### Building Debug APK
```bash
./gradlew assembleDebug
```

---

## 📜 Documentation

Detailed architecture specifications, decisions, and pipeline documentation can be found in [`docs/`](docs/):
- [`docs/AUDIT.md`](docs/AUDIT.md): Current audit: complete / untested / missing / blocked, and overclaims fixed
- [`docs/PROOF_GATES.md`](docs/PROOF_GATES.md): Empirical proof gates, global proof flags and the exact device procedures for Gates 1–3
- [`docs/TEMPORAL_PIPELINE.md`](docs/TEMPORAL_PIPELINE.md): Classical temporal burst merge: design, synthetic evidence, limits
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): System architecture and design principles
- [`docs/CAMERA_PIPELINE.md`](docs/CAMERA_PIPELINE.md): Camera2 pipeline and frame lifecycle
- [`docs/DEVICE_PROFILES.md`](docs/DEVICE_PROFILES.md): Hardware capability mapping
- [`docs/DECISIONS.md`](docs/DECISIONS.md): Architectural Decision Records (ADRs)
- [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md): Performance budgets and benchmarking methodology
- [`docs/RELEASE.md`](docs/RELEASE.md): Release process and deployment
