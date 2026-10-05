# Neural Camera System Architecture & Development Constitution

## 1. High-Level Vision & Operating Philosophy
Neural Camera is a rootless, device-adaptive computational photography platform built for modern Android devices. Its target is to function as a professional photographic instrument, combining modern Camera2 controls with hardware-accelerated neural image processing while maintaining strict fidelity to physical reality.

Unlike naive camera applications that wrap camera previews with post-processing filters, Neural Camera treats capture as an integrated runtime graph:
- Intelligently plans multi-frame exposures before shutter release.
- Captures synchronized sensor telemetry (gyro, accel, exposure, timestamps).
- Executes temporal alignment and neural/classical reconstruction on validated accelerators (Qualcomm QNN NPU, Vulkan GPU, XNNPACK CPU).
- Inspects reconstructed images with Reality Guard to prevent synthetic hallucinations.
- Non-destructively preserves the original sensor data alongside the Master photograph.

---

## 2. Multi-Module Topology & Dependency Direction

The architecture enforces a strict one-directional dependency graph:
```
UI
 ↓
Application Orchestration (:app)
 ↓
Camera / Capture (:camera-core, :capture-intelligence)
 ↓
Processing / Runtime (:neural-runtime, :neural-isp, :quality-engine, :video-engine)
 ↓
Output (:gallery)
```
Supporting horizontal systems integrate exclusively through explicit interfaces:
- `:models`
- `:device-profiles`
- `:benchmarks`
- `:data-lab`

```mermaid
graph TD
    App[app] --> UI[ui]
    App --> CameraCore[camera-core]
    App --> CaptureIntell[capture-intelligence]
    App --> NeuralRuntime[neural-runtime]
    App --> NeuralIsp[neural-isp]
    App --> QualityEngine[quality-engine]
    App --> VideoEngine[video-engine]
    App --> Gallery[gallery]
    App --> Benchmarks[benchmarks]
    App --> DataLab[data-lab]

    UI --> CameraCore
    UI --> CaptureIntell
    UI --> NeuralIsp
    UI --> QualityEngine
    UI --> Gallery

    CaptureIntell --> CameraCore
    CaptureIntell --> DeviceProfiles[device-profiles]

    NeuralIsp --> CameraCore
    NeuralIsp --> NeuralRuntime
    NeuralIsp --> QualityEngine

    NeuralRuntime --> Models[models]
    NeuralRuntime --> DeviceProfiles
    NeuralRuntime --> Benchmarks

    VideoEngine --> CameraCore
    VideoEngine --> NeuralRuntime
    VideoEngine --> QualityEngine

    QualityEngine --> Models
    QualityEngine --> DeviceProfiles

    Gallery --> CameraCore
    Gallery --> Models
```

### Module Responsibilities:
1. `:models`: Strict model descriptors, precision types (FP32, FP16, INT8, INT4), hardware backend targets, compatibility contracts, and manifest validators.
2. `:device-profiles`: Declarative versioned device capabilities, camera specs, thermal thresholds, and device profiles (OnePlus 15, Generic Flagship, Generic Fallback).
3. `:benchmarks`: Empirical benchmarking engine, latency percentiles (median, p95, p99), memory peak tracking, privacy-safe telemetry, and 13-category structured logger.
4. `:quality-engine`: Image quality evaluator, sensor confidence estimator, and Reality Guard hallucination protection.
5. `:camera-core`: Camera2 contracts, deterministic state machine (`CameraState`), structured error hierarchy (`CameraSystemError`), buffer leasing contracts (`FrameBufferHandle`), and bounded ring buffer repositories.
6. `:capture-intelligence`: Scene luminance & motion estimation, universal capture planning across shooting modes (AUTO, PRO, MASTER, AUTHENTIC).
7. `:neural-runtime`: Model registry, adaptive scheduler, thermal budget manager, memory allocation manager, hardware backends, and `InferenceRuntime`.
8. `:neural-isp`: Multi-frame temporal fusion, classical baseline ISP fallback pipeline (Zero Fake AI).
9. `:video-engine`: Video pipeline enforcing bounded compute per frame (selective keyframe enhancement, hardware passthrough).
10. `:gallery`: Non-destructive dual-storage repository (Original + Master + JSON metadata).
11. `:ui`: Minimalist, Leica/Zeiss-inspired Jetpack Compose user interface, tactile lens selector, mode carousel, subtle NEURAL status, diagnostics sheet.
12. `:data-lab`: Reference regression dataset (14 controlled scenes) and automated regression verification.
13. `:app`: Application entry point, dependency injection, and activity lifecycle management.

---

## 3. Threading Architecture & Concurrency Rules

In adherence to Section 9 of the Constitution:
- **Main / UI Thread**: Solely reserved for Compose UI rendering and state observation. Zero disk IO, zero buffer allocations, zero model inferences.
- **Camera Dispatcher**: Dedicated handler/dispatcher for Camera2 device callbacks, state callbacks, and request queue dispatching.
- **Frame Analysis Dispatcher**: Lightweight preview analysis, scene lux calculation, and gyroscope motion integration.
- **Neural Inference Dispatcher**: Off-thread background executor for hardware accelerator invocation (NPU / GPU / CPU).
- **Image Processing Dispatcher**: High-performance multi-threaded compute for classical ISP routines, spatial filtering, and tone mapping.
- **IO / Storage Dispatcher**: Dedicated asynchronous IO dispatcher for MediaStore insertion, DNG writing, and metadata serialization.
- **Structured Cancellation**: When `CameraDeviceController.closeCamera()` is invoked, all downstream in-flight capture jobs and coroutine scopes are cancelled safely without leaking hardware handles.

---

## 4. Frame Ownership & Buffer Lifecycle Model

In adherence to Section 8 of the Constitution:
- **Ownership Principles**:
  - Image buffers are owned by the acquiring subsystem (`CameraCore` or `FrameSource`).
  - No unbounded frame retention: frames reside in a `BoundedRingFrameRepository` with strict byte and capacity ceilings.
  - Consumers must borrow buffers via `FrameBufferLease`. Releasing the lease decrements the lease count. When all leases close, the underlying buffer transitions to `RELEASED`.
  - Buffers may cross thread boundaries only through thread-safe handles (`ReferenceCountedBufferHandle`).
  - Underlying memory abstractions support: `Image`, `HardwareBuffer` (zero-copy GPU/NPU interop), `Surface`, `NativeDirectBuffer`, and `ByteBuffer`.

---

## 5. Camera State Management

In adherence to Section 10 of the Constitution:
Camera lifecycle transitions are governed deterministically by `CameraStateMachine` using `CameraState`:
```
UNINITIALIZED -> INITIALIZING -> READY <-> FOCUSING
                                   │  ↑
                                   ↓  │
                                CAPTURING
                                   │
                                   ↓
                               PROCESSING
                                   │
                                   ↓
                                 SAVING
                                   │
                                   ↓
                                 READY
```
- In case of failure: any active state transitions to `ERROR`.
- `ERROR` transitions to `RECOVERING`, which can re-enter `INITIALIZING` or `READY`.
- `CLOSED` cleanly closes all hardware streams.

---

## 6. Structured Error Model

In adherence to Section 11 of the Constitution, generic exceptions are prohibited as the primary error mechanism. The system defines `sealed class CameraSystemError`:
1. `ExpectedUnsupportedCapability`: E.g. RAW format unsupported on secondary lens, 10-bit HDR not supported. Fully recoverable.
2. `RecoverableRuntimeFailure`: E.g. AI backend failed to load, inference timeout, thermal throttling forced classical fallback. Recoverable.
3. `CameraHardwareFailure`: E.g. camera disconnected, camera in use by another app.
4. `ResourceFailure`: E.g. insufficient RAM, insufficient disk storage, ring buffer overflow.
5. `FatalApplicationFailure`: E.g. unrecoverable HAL initialization crash.

---

## 7. Storage Architecture & Non-Destructive Invariant

In adherence to Section 18 of the Constitution:
- **OriginalCapture**: Untampered RAW/DNG or sensor image buffer.
- **ProcessedImage**: Intermediate image after tone mapping and multi-frame fusion.
- **NeuralMaster**: Final calibrated image with high perceptual fidelity.
- **Metadata**: JSON capture & processing telemetry saved alongside image files.
- **TemporaryProcessingData**: Transient scratch buffers deleted immediately after fusion completes.
- **Invariant**: The original capture is never overwritten or destroyed, ensuring user photographic data is permanently safeguarded.

---

## 8. Phase 1 Implementation & Scope Boundary

- **Phase 1 Scope Completed**:
  - Real Camera2 capability resolver (`DeviceCapabilityResolver`) interrogating identity, sensor active array, focal lengths, apertures, 3A modes, stream formats, dynamic range profiles, stream use cases, and Android 16 capabilities.
  - Logical-to-physical camera mapping (`LogicalToPhysicalCameraMap`) mapping logical camera 0 to constituent physical lenses without numerical ID assumptions.
  - Stream configuration engine (`CameraSessionPlanner`) with capability-aware stream size selectors.
  - Development stream test matrix (`StreamMatrixTester`) recording `SUPPORTED`, `SESSION_CREATION_SUCCESS`, `ACTUAL_CAPTURE_SUCCESS`, and `FAILED`.
  - Live hardware preview pipeline via `SurfaceView` (`CameraViewport`) preserving Leica/Zeiss minimalist aesthetic.
  - Production-safe `ImageReaderManager` with backpressure control, guaranteed image closure, and leak accounting.
  - Real `CapturedFrame` model supporting temporal pipelines with synchronized IMU telemetry.
  - Decoupled `CaptureResultAssociator` tolerating delayed metadata, delayed images, and dropped frames.
  - `SensorTimelineSynchronizer` validating `CLOCK_BOOTTIME` domain and interpolating gyro/accel samples.
  - `ZeroCopyAuditor` auditing memory movements and evaluating zero-copy gates.
  - Full debug camera diagnostic sheet (`CameraDiagnosticsSheet`) displaying all 20 required telemetry fields.
  - Deterministic state machine integration and bounded error recovery in `RealCamera2Controller`.
  - ADB developer tooling (`tools/camera_dev_tools.sh`).
  - Runtime capability profiles generated at `profiles/runtime/OnePlus_15/20260925_000000/` reflecting authoritative hardware re-audit: SM8850 / canoe (Snapdragon 8 Elite Gen 5), 3rd-generation Qualcomm Oryon CPU, Qualcomm Adreno 840 GPU, Qualcomm Hexagon HTP V81 cDSP, 5 LEVEL_3 cameras [0, 1, 2, 3, 4], and STMicroelectronics lsm6dsv 480Hz IMU.

- **Strictly Deferred to Future Phases (Do Not Implement in Phase 1)**:
  - Neural ISP models, deep denoising, deblurring.
  - Temporal neural reconstruction & fusion.
  - Final Capture Director & semantic exposure AI.
  - Generative AI, FLUX, OmniNeural, MobileWan.
  - Cross-device adaptation & model quantization.
