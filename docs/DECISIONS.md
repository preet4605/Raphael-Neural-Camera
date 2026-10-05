# Architectural Decision Records (ADR)

## ADR-001: Multi-Module Architecture Decomposition
- **Decision**: Decompose the Neural Camera system into 13 decoupled, testable modules (`:app`, `:camera-core`, `:capture-intelligence`, `:neural-runtime`, `:neural-isp`, `:quality-engine`, `:video-engine`, `:device-profiles`, `:models`, `:benchmarks`, `:gallery`, `:ui`, `:data-lab`).
- **Context**: Monolithic Android camera apps frequently suffer from tight coupling between Camera2 callbacks, UI view logic, and computational post-processing, resulting in maintainability bottlenecks and race conditions.
- **Alternatives**: Single-module app structure; 3-tier presentation/domain/data architecture.
- **Chosen approach**: 13 domain-aligned modules with strict one-directional dependency rules (`:ui` -> `:app` -> `:camera-core` -> `:neural-runtime` -> `:gallery`).
- **Reason**: Guarantees clear module boundaries, fast incremental compilation, isolated unit testability, and prevents UI code from directly importing AI inference internals.
- **Trade-offs**: Slightly higher initial Gradle configuration overhead and inter-module contract maintenance.
- **Future implications**: Ensures future phases (e.g. real neural ISP, Qualcomm QNN NPU integration) can be implemented without refactoring project structure.

---

## ADR-002: Camera2 Low-Level Interop Over Pure CameraX
- **Decision**: Base core capture and sensor controls directly on Camera2 HAL interfaces while using CameraX patterns only where beneficial for lifecycle binding.
- **Context**: Neural Camera requires fine-grained control over manual exposure timing, physical multi-camera lens selection, uncompressed RAW10/12 stream capture, and zero-shutter-lag synchronization on Tier-1 hardware (OnePlus 15).
- **Alternatives**: Pure CameraX with high-level use cases; Vendor Camera Extensions exclusively.
- **Chosen approach**: Strongly typed Camera2 contract layer in `:camera-core` managing raw capture requests, physical sensor interrogation, and bounded frame ring buffers.
- **Reason**: CameraX abstracts away physical camera IDs, manual sensor exposure limits, and low-level hardware buffer attachments required for neural ISP execution.
- **Trade-offs**: Requires explicit handling of camera device lifecycles and HAL error codes.
- **Future implications**: Enables direct zero-copy stream binding to Qualcomm Hexagon NPU and Vulkan compute shaders in Phase 1 and 2.

---

## ADR-003: Zero Fake AI & Ground-Truth Baseline Fallback
- **Decision**: Reject simulated sharpening filters or synthetic enhancements labeled as "AI". Implement `BaselineImagePipeline` with true classical multi-frame temporal fusion as a permanent reference fallback.
- **Context**: Many commercial camera applications apply uncalibrated unsharp masking or fake HDR color filters and advertise them as neural reconstruction, compromising photographic integrity.
- **Alternatives**: Stubbing AI models with simple bilateral filters; bundling placeholder generative models.
- **Chosen approach**: Classical multi-frame temporal fusion and tone mapping in `:neural-isp` that serves as ground truth and verified fallback whenever neural models cannot execute within budget.
- **Reason**: Adheres to the core product philosophy ("Zero Fake AI") and provides a reliable baseline for empirical PSNR/SSIM benchmarking.
- **Trade-offs**: Requires building and maintaining both classical and neural processing pipelines.
- **Future implications**: Enables automated regression testing where neural ISP outputs are mathematically compared against classical baseline outputs in `:data-lab`.

---

## ADR-004: Reality Guard Hallucination Protection
- **Decision**: Implement `RealityGuard` and `ConfidenceEstimator` in `:quality-engine` to prevent synthetic hallucinations in reconstructed imagery.
- **Context**: Deep neural super-resolution and generative restoration models risk hallucinating high-frequency details (e.g., synthetic skin textures, altered text, false eye details) not present in the original sensor data.
- **Alternatives**: Unrestricted neural inference; purely global confidence scores without spatial maps.
- **Chosen approach**: Spatial tile confidence estimation combined with pixel divergence checking. High-confidence sensor regions are preserved by blending back ground truth or reverting the neural pass.
- **Reason**: Protects photographic authenticity and user trust.
- **Trade-offs**: Incurs slight compute latency (evaluating tile divergence maps) before saving.
- **Future implications**: Establishes a verifiable quality gate required for professional photo certification.

---

## ADR-005: Frame Ownership & Reference-Counted Buffer Leasing
- **Decision**: Introduce explicit buffer lifecycle contracts (`FrameBufferHandle`, `FrameBufferLease`) with bounded circular repositories to eliminate memory leaks and out-of-memory crashes.
- **Context**: 50MP RAW and 4K RGBA image buffers consume between 50MB and 200MB per burst. Unbounded retention or garbage-collection-dependent release quickly triggers OOM in high-speed capture.
- **Alternatives**: Relying solely on JVM GC; unbounded LinkedList frame queues.
- **Chosen approach**: `BoundedRingFrameRepository` with byte and capacity limits, paired with explicit `FrameBufferLease` reference counting.
- **Reason**: Guarantees deterministic memory ceilings and prevents buffer reuse while downstream processing is active.
- **Trade-offs**: Downstream components must explicitly close borrowed leases.
- **Future implications**: Prepares zero-copy memory transfers between Camera2 `ImageReader`, `HardwareBuffer`, and NPU tensors.

---

## ADR-006: Dedicated Threading Dispatcher Architecture
- **Decision**: Define structured coroutine dispatchers in `CameraDispatchers` separating UI, Camera, Frame Analysis, Neural Inference, Image Processing, and IO operations.
- **Context**: Mixing camera callbacks, neural inference, and disk writes on shared threads causes preview stutter, frame drops, and ANR crashes.
- **Alternatives**: Single `Dispatchers.Default` for all non-UI tasks; raw Java thread pools.
- **Chosen approach**: Explicit `CameraDispatchers` contract defining isolated threads for each subsystem with structured cancellation on camera close.
- **Reason**: Prevents thread contention and enforces structured concurrency.
- **Trade-offs**: Slight boilerplate when passing dispatchers via dependency injection.
- **Future implications**: Simplifies profiling and thread priority tuning on heterogenous big.LITTLE mobile CPUs.

---

## ADR-007: Non-Destructive Dual Storage (Original + Master)
- **Decision**: Persist both the original sensor capture (`OriginalCapture`) and the final photographic output (`NeuralMaster`) alongside comprehensive metadata JSON.
- **Context**: Post-processing failures or aggressive algorithmic artifacting must never destroy the user's raw photograph.
- **Alternatives**: Overwrite original capture with processed output; save only processed JPEG.
- **Chosen approach**: `OriginalMasterMediaRepository` non-destructively saves raw/original bytes, neural master bytes, and diagnostic metadata in isolated files.
- **Reason**: Preserves user photographic data unconditionally.
- **Trade-offs**: Increases disk storage utilization per capture.
- **Future implications**: Enables future re-processing of captures when newer neural models are deployed.

---

## ADR-008: Selective Keyframe Video Neural Processing
- **Decision**: Restrict video neural enhancement to selective keyframes (e.g. 1 in 15 frames) while streaming intermediate frames directly to hardware encoders.
- **Context**: Processing 30fps or 60fps video with full per-frame neural ISP on mobile devices causes immediate thermal throttling, severe battery drain, and dropped frames.
- **Alternatives**: Per-frame full neural inference; purely classical video capture.
- **Chosen approach**: Keyframe neural parameter estimation with temporal parameter interpolation and hardware `MediaCodec` passthrough.
- **Reason**: Keeps total video pipeline latency within bounded 33ms/16ms frame deadlines.
- **Trade-offs**: Full neural reconstruction is not applied to every individual video frame.
- **Future implications**: Ensures sustained 4K 60fps recording without thermal shutdowns.

---

## ADR-009: Authoritative Camera2 HAL Control vs CameraX Integration Boundary
- **Decision**: Designate low-level Camera2 HAL3 interfaces as the sole authoritative layer for physical multi-camera control, stream planning, dynamic range profile selection, hardware buffer attachment, and manual capture requests, while restricting CameraX to non-critical lifecycle-aware composition if required.
- **Context**: Neural Camera Phase 1 requires interrogation of physical constituent sensor characteristics (`CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`), uncompressed RAW10/RAW_SENSOR streams, stream use cases (`SCALER_AVAILABLE_STREAM_USE_CASES`), 10-bit HDR dynamic range constraints, and direct zero-copy `HardwareBuffer` attachments for future neural accelerator pipelines. High-level CameraX use cases abstract away physical camera IDs and internal buffer allocation queues.
- **Alternatives**: Complete migration to CameraX 1.6.2; experimental CameraX 1.7 alpha; pure NDK ACamera2.
- **Chosen approach**: Strongly typed Kotlin Camera2 layer in `:camera-core` (`RealCamera2Controller`, `CameraSessionPlanner`, `DeviceCapabilityResolver`, `ImageReaderManager`).
- **Reason**: Guarantees zero abstraction loss, strict deterministic state transitions, direct control over buffer queues, and predictable frame latency.
- **Trade-offs**: Requires explicit management of `CameraDevice.StateCallback`, `CameraCaptureSession.StateCallback`, and background handler threads.
- **Future implications**: Enables direct zero-copy buffer sharing with Qualcomm QNN HTP NPU and Vulkan compute shaders in Phase 2.

