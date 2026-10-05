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
| 2 — Neural runtime foundation | **NEXT — not started** |
| 3+ — Computational photography / Neural ISP | After runtime validation |

Earlier commit messages that call Phase 2 complete are wrong: there is no QNN/Vulkan/CPU inference backend, no test network and no backend attribution yet.

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
- Burst capture: `RealCamera2Controller.triggerBurstCapture` returns no frames, so the shutter reports an error instead of saving anything.
- Neural inference: no backend exists; `StandardInferenceRuntime` throws `BackendUnavailableException` rather than returning unprocessed input. The model catalog is placeholders, all `UNVERIFIED` with no measured numbers.
- Image pipeline: `BaselineImagePipeline` is a luma-only per-pixel mean over frames, with no alignment or motion rejection, and writes grayscale output. It is not the motion-aware merge the design requires.
- Encoding: there is no JPEG/DNG/HEIF encoder. Saved files are unencoded planes (`.raw`).
- Reality Guard: a heuristic comparison of reconstructed vs. original luma. It is not hallucination detection and not cryptographic provenance.
- Zero-copy, IMU sync accuracy, thermal behavior and latency: none measured.

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
| `:neural-runtime` | Model registry, scheduler, thermal/memory budgeting and the inference-runtime contract. No backend (QNN, Vulkan, CPU) is implemented yet. |
| `:neural-isp` | Classical luma-only frame-averaging baseline. Neural demosaic, denoise and HDR fusion are planned. |
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
- [`docs/PROOF_GATES.md`](docs/PROOF_GATES.md): Empirical proof gates and global proof flags
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): System architecture and design principles
- [`docs/CAMERA_PIPELINE.md`](docs/CAMERA_PIPELINE.md): Camera2 pipeline and frame lifecycle
- [`docs/DEVICE_PROFILES.md`](docs/DEVICE_PROFILES.md): Hardware capability mapping
- [`docs/DECISIONS.md`](docs/DECISIONS.md): Architectural Decision Records (ADRs)
- [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md): Performance budgets and benchmarking methodology
- [`docs/RELEASE.md`](docs/RELEASE.md): Release process and deployment
