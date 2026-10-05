# Raphael Neural Camera

A device-adaptive computational photography platform and Camera2 engine built for modern Android architectures (Snapdragon 8 Elite Gen 5 / Android 16 API 36+).

Raphael Neural Camera treats image and video capture as an integrated runtime graph—combining multi-frame exposure planning, synchronized IMU/sensor telemetry, zero-copy buffer pools, and hardware-accelerated neural image processing (QNN NPU, Vulkan GPU, XNNPACK CPU) with strict fidelity to physical reality.

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
| `:camera-core` | Low-level Camera2 controller, session planner, zero-copy ring buffers, and sensor timeline synchronizer. |
| `:capture-intelligence` | Scene metering, exposure bracket planning, and motion/jitter analysis. |
| `:neural-runtime` | Hardware-accelerated inference backend (Qualcomm QNN NPU, Vulkan GPU, CPU). |
| `:neural-isp` | Neural demosaicing, noise reconstruction, and HDR exposure fusion. |
| `:quality-engine` | Image validation and Reality Guard (preventing hallucinated textures). |
| `:video-engine` | Real-time video frame processing, stabilization, and color pipelines. |
| `:device-profiles` | Hardware capability matrices, profile exporters, and fallback configurations. |
| `:models` | Neural network model descriptors, manifests, and integrity verification. |
| `:benchmarks` | Latency, throughput, and memory profiling telemetry. |
| `:gallery` | Non-destructive RAW/Master storage and metadata repository. |
| `:ui` | Jetpack Compose photographic viewfinder, controls, and telemetry HUD. |
| `:data-lab` | Diagnostic tools and dataset collection utilities. |

---

## 🚀 Key Features

- **Direct Camera2 Architecture**: Full manual control over ISO, shutter speed, exposure compensation, and focal distance.
- **Zero-Copy Ring Buffers**: High-performance frame recycling without GC churn during continuous preview and burst capture.
- **Sensor Timeline Synchronization**: Microsecond-accurate alignment of gyroscope, accelerometer, and capture result metadata with image frames.
- **Device Profiles**: Dynamic capability resolution tailored for flagship hardware (e.g. OnePlus 15 / Snapdragon 8 Elite).
- **Reality Guard**: Hallucination-resistant neural reconstruction ensuring physical image fidelity.
- **Jetpack Compose UI**: Modern, accessible darkroom-inspired dark UI with live exposure telemetry.

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
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): System architecture and design principles
- [`docs/CAMERA_PIPELINE.md`](docs/CAMERA_PIPELINE.md): Camera2 pipeline and frame lifecycle
- [`docs/DEVICE_PROFILES.md`](docs/DEVICE_PROFILES.md): Hardware capability mapping
- [`docs/DECISIONS.md`](docs/DECISIONS.md): Architectural Decision Records (ADRs)
- [`docs/BENCHMARKING.md`](docs/BENCHMARKING.md): Performance budgets and benchmarking methodology
- [`docs/RELEASE.md`](docs/RELEASE.md): Release process and deployment
