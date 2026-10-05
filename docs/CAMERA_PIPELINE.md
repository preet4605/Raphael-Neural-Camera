# Camera Acquisition & Processing Pipeline

## 1. Pipeline Graph & Dataflow Architecture
The camera acquisition pipeline isolates high-rate sensor frame acquisition from presentation, inference, and storage:

```
Camera Sensor (Camera2 HAL3)
      │
      ▼
ImageReader / Surface (RAW_SENSOR / YUV_420_888 / HardwareBuffer)
      │
      ▼
FrameSynchronizer (Hardware Timestamps + IMU Gyro/Accel + Metadata)
      │
      ▼
BoundedRingFrameRepository (Bounded Ring Buffer with Strict Capacity & Byte Limits)
      │
      ▼
Capture Intelligence (UniversalCapturePlanner: Frame Count & Exposure Strategy)
      │
      ▼
PipelineScheduler (Thermal + Memory + Latency Evaluation)
      │
      ▼
Neural ISP / Classical Baseline ISP (Multi-frame Temporal Fusion & Optical Correction)
      │
      ▼
RealityGuard (Hallucination Detection, Divergence Check & Ground-Truth Blending)
      │
      ▼
Non-Destructive Storage (Original Sensor Plane + Master Photograph + Telemetry JSON)
```

---

## 2. Buffer Ownership & Lifecycle Model (Section 8)

Image buffers represent the highest memory risk in mobile computational photography. The pipeline enforces strict ownership invariants:
1. **Acquisition Ownership**: Image buffers are owned by `CameraCore` upon arrival in the `ImageReader` or `Surface`.
2. **Bounded Buffer Ring**: `BoundedRingFrameRepository` maintains a maximum capacity (e.g., 16 frames) and byte limit (e.g., 256MB). Oldest unpinned frames are evicted automatically when capacity is reached.
3. **Leasing & Pinning**: Downstream consumers (e.g. `UniversalCapturePlanner`, `ImagePipeline`) must acquire a `FrameBufferLease`. A buffer cannot be evicted or reused while an active lease is held.
4. **Thread Boundary Crossing**: Buffers cross thread boundaries only via thread-safe `ReferenceCountedBufferHandle` instances.
5. **Memory Formats**:
   - `IMAGE`: `android.media.Image` from Camera2.
   - `HARDWARE_BUFFER`: `android.hardware.HardwareBuffer` for zero-copy NPU/GPU access.
   - `SURFACE`: Direct hardware preview surface.
   - `NATIVE_DIRECT_BUFFER`: Direct memory for JNI C++ processing.
   - `BYTE_BUFFER`: Direct or heap byte buffer fallback.

---

## 3. Pipeline Threading Model (Section 9)

Every stage of the pipeline runs on designated, non-blocking coroutine dispatchers:
- **Camera Dispatcher**: Drives Camera2 sessions, capture requests, and state callbacks on dedicated background handler threads.
- **Frame Analysis Dispatcher**: Calculates luminance histograms, motion vectors, and gyroscope integration.
- **Inference Dispatcher**: Invokes neural accelerator backends (QNN NPU, Vulkan GPU, XNNPACK CPU) strictly off-thread.
- **Image Processing Dispatcher**: Executes classical multi-frame fusion, bilateral filtering, and demosaicing.
- **IO Dispatcher**: Performs asynchronous MediaStore persistence and disk writes.
- **Main / UI**: Updates Compose UI state only. Never blocks on disk or compute.

---

## 4. Shooting Modes & Capture Strategy

- **AUTO**: Intelligent exposure balancing; calculates temporal depth (1 to 8 frames) based on scene lux and motion vectors.
- **PRO**: Manual photographic controls (ISO 50–25600, shutter 1/32000s to 30s, EV bias, manual focus distance), uncompressed RAW10/12 stream capture.
- **MASTER**: Extended burst acquisition (up to 16 frames), exposure bracketing, wide dynamic range tone reconstruction.
- **AUTHENTIC**: Ground-truth prioritized rendering with zero synthetic texture generation, conservative deblurring, and natural chromatic reproduction.

---

## 5. Reality Guard Safety Invariant

High-confidence sensor regions are never altered by synthetic neural generation. If neural reconstruction deviates from ground truth beyond threshold limits, Reality Guard automatically reverts or blends back original sensor pixels.

---

## 6. Discovered Camera Inventory & Logical/Physical Mapping

Interrogated from physical host OnePlus 15 (CPH2745, Android 16 API 36, SM8850 Snapdragon 8 Elite Gen 5):
- **All Cameras Hardware Level**: `LEVEL_3` across all 5 devices.
- **Logical Camera 0**: Lens Facing `BACK_WIDE`, Sensor Orientation 90°.
  - Active Array: 8192 x 6144 (50 MP).
  - Constituent Physical Lenses: `["3", "2", "4"]`.
  - Capabilities: `RAW_SENSOR`, `RAW10`, `LOGICAL_MULTI_CAMERA`, `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`.
  - Primary Lens: 5.59 mm, f/1.88, OIS.
- **Logical Camera 1**: Lens Facing `FRONT`, Sensor Orientation 270°.
  - Active Array: 3280 x 2464, 3.23 mm, f/2.4.
- **Physical Camera 2**: Lens Facing `BACK_WIDE` (Main Wide Physical Sensor).
  - Active Array: 8192 x 6144 full / 4096 x 3072 binned (50 MP), 5.59 mm, f/1.88, OIS.
  - Formats: `RAW_SENSOR`, `RAW10`, `YUV_420_888`, `JPEG`.
- **Physical Camera 3**: Lens Facing `BACK_ULTRAWIDE` (Ultra-Wide Physical Sensor).
  - Active Array: 4096 x 3072, 2.31 mm, f/2.0.
  - Formats: `RAW_SENSOR`, `YUV_420_888`, `JPEG`.
- **Physical Camera 4**: Lens Facing `BACK_TELEPHOTO` (Telephoto / Periscope Physical Sensor).
  - Active Array: 4096 x 3072, 12.19 mm, f/2.85 (~70-75 mm eq).
  - Formats: `RAW_SENSOR`, `YUV_420_888`, `JPEG`.
- **HAL Direct Ultra HDR**: Disabled via vendor property `ro.camera.disableHeicUltraHDR=true`. Direct HAL HEIF Ultra HDR and direct HAL JPEG_R are disabled; gainmap HDR generation is performed in the software pipeline. Hardware HEIC encoder present: `c2.qti.heic.encoder`.

---

## 7. Stream Configuration Matrix

Validated via `CameraSessionPlanner` and `StreamMatrixTester`:
| Stream Combination | Target Streams | Hardware Status | Latency |
|---|---|---|---|
| Preview Only | SurfaceView (1920x1080) | SUPPORTED | 8 ms |
| Preview + YUV | Surface (1080p) + YUV (720p) | SUPPORTED | 14 ms |
| Preview + Still JPEG | Surface (1080p) + JPEG (12MP) | SUPPORTED | 12 ms |
| Preview + Still RAW | Surface (1080p) + RAW_SENSOR (50MP) | SUPPORTED | 22 ms |
| Preview + YUV + JPEG | Surface (1080p) + YUV (720p) + JPEG (12MP) | SUPPORTED | 18 ms |
| Preview + 4K Video | Surface (1080p) + Surface (3840x2160) | SUPPORTED | 15 ms |

---

## 8. ImageReader Backpressure & Lifecycle Accounting

`ImageReaderManager` enforces strict lifecycle constraints:
- Maximum queue depth: 4 images for preview analysis, 5 images for burst capture.
- Guaranteed closure: Oldest unleased images are evicted and closed when backpressure approaches capacity.
- Zero-leak accounting: Tracks `acquiredCount`, `closedCount`, `outstandingCount`, and `droppedCount`.

---

## 9. Capture Result Association & Out-of-Order Handling

`CaptureResultAssociator` associates `android.media.Image` with Camera2 `TotalCaptureResult`:
- Matching Key: Monotonic `SENSOR_TIMESTAMP` in nanoseconds.
- Decoupled Arrival: Tolerates image arrival before result or result arrival before image.
- Dropped Frame Protection: Stale pending entries (>1000ms) are evicted and closed automatically.

---

## 10. IMU Sensor Synchronization & Clock Domain Timing

`SensorTimelineSynchronizer` integrates hardware IMU telemetry:
- Sensors: STMicroelectronics `lsm6dsv` Accelerometer and Gyroscope sampled at ~479.85 Hz (~480 Hz nominal).
- Clock Domain: Both Camera2 `SENSOR_TIMESTAMP` and `SensorEvent.timestamp` reside in monotonic `CLOCK_BOOTTIME`.
- Temporal Synchronization: Linear interpolation $(v = v_0 + (v_1 - v_0) \cdot \alpha)$ computes motion vectors at exact shutter timestamps with median jitter $< 0.4\text{ms}$.

---

## 11. Zero-Copy Audit & Buffer Copies

Audited via `ZeroCopyAuditor` (Section 13 and Section 41):
- **Camera HAL -> SurfaceView**: 0 copies (Direct SurfaceFlinger hardware scanout overlay, VERIFIED).
- **Camera HAL -> HardwareBuffer**: 0 copies (Direct `AHardwareBuffer` mapping to Vulkan/QNN memory; DESIGNED, model execution NOT_TESTED in Phase 1).
- **Camera HAL -> ImageReader ByteBuffer -> JVM**: 1 copy (Occurs only when CPU requests JVM `ByteArray` copies).
- **Image Processing -> MediaStore**: 1 copy (Page cache DMA to UFS 4.0 flash storage).
- **Audit Gate Assessment**:
  - Zero-Copy Design: **PASS**
  - Zero-Copy Actual Hardware Path: **PARTIAL** (Direct hardware surfaces scanout is 0-copy; NPU/GPU model execution zero-copy path is NOT_TESTED; JVM array fallbacks incur 1 copy).
