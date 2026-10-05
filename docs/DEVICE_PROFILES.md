# Device Profiles & Capability Interrogation

## 1. Multi-Tier Strategy
The universal camera pipeline adapts dynamically to the verified capabilities of the host device:

### Tier 1 Reference Device: OnePlus 15 (12 GB RAM, Android 16)
- **SoC Identity**:
  - Technical / Platform Identifier: `SM8850` / `canoe` (SoC ID: `660`)
  - Commercial Name: `Snapdragon 8 Elite Gen 5` (replaces any provisional references to SM8750 or Snapdragon 8 Elite Gen 2)
- **CPU**: `3rd-generation Qualcomm Oryon` (8 cores: 6 Performance max 3.6288 GHz, 2 Prime max 4.6080 GHz; ARMv9.2-A, Implementer 0x51, Part 0x002; NEON, FP16, DOTPROD, I8MM, BF16, SVE, SVE2, SME).
- **GPU**: `Qualcomm Adreno 840` (OpenGL ES 3.2, Vulkan 1.4.0; compute available; model execution `NOT_TESTED`).
- **NPU / AI Runtime**: `Qualcomm Hexagon` HTP `V81`, cDSP (`libQnnHtp.so`, `libQnnHtpV81Stub.so`, `libQnnHtpV81Skel.so`, `libQnnGpu.so`, `libQnnCpu.so`; QNN version `2.37.4`; model execution `NOT_TESTED`).
- **Memory**: 12 GB RAM (Android-visible ~11.38 GB / 12,884,901,888 bytes nominal), ZRAM ~12 GB.
- **Sensors (5 LEVEL_3 Devices)**:
  - **Camera 0**: Logical rear multi-camera combining physical IDs `["3", "2", "4"]`.
  - **Camera 1**: Front camera (3.23 mm, f/2.4, 3280x2464).
  - **Camera 2**: Main wide physical (5.59 mm, f/1.88, 50 MP, 4096x3072 binned / 8192x6144 full, OIS).
  - **Camera 3**: Ultra-wide physical (2.31 mm, f/2.0, 4096x3072).
  - **Camera 4**: Telephoto / periscope physical (12.19 mm, f/2.85, 4096x3072, ~70-75 mm eq).
- **HEIF / Ultra HDR**: `ro.camera.disableHeicUltraHDR=true` (direct HAL HEIF Ultra HDR & direct HAL JPEG_R disabled; generated via software gainmap pipeline; hardware HEIC encoder: `c2.qti.heic.encoder`).
- **IMU**: STMicroelectronics `lsm6dsv` gyro & accelerometer (~479.85 Hz) on monotonic `CLOCK_BOOTTIME`.
- **Memory Ceiling**: 512 MB Frame Ring Buffer, 2.5 GB Model Residency.

### Tier 2: Generic Flagship
- Modern Android 14+ flagship with >= 8 GB RAM, RAW sensor support, Vulkan GPU acceleration.

### Tier 3: Generic Fallback
- Standard Android device, YUV 420 streams only, CPU execution, bounded 96 MB ring buffer.

---

## 2. Provisional vs Discovered vs Authoritative Hardware Re-Audit

In accordance with Phase 1 Section 2 ("Actual Device Capabilities Win"), provisional specifications were audited and corrected via direct hardware interrogation on the physical OnePlus 15:

- **Capability State Hierarchy**:
  - `DECLARED`: Statically specified in device documentation.
  - `DISCOVERED`: Read directly from Camera2 `CameraCharacteristics` and system properties.
  - `VERIFIED`: Proven by successful hardware acquisition and benchmark validation.
  - `NOT_TESTED`: Identified in hardware/HAL but deferred to Phase 2 (e.g. neural runtime execution).
  - `DISABLED_BY_HAL`: Blocked or disabled by vendor HAL property.

| Subsystem / Capability | Provisional Spec | Authoritative Physical Baseline | Validation State |
|---|---|---|---|
| Host Model | OnePlus 15 | `CPH2745` (Product: CPH2745IN, Device: OP611FL1, Board: canoe) | DISCOVERED |
| OS / Kernel | Android 16 | Android 16 (API 36), Kernel 6.12.23-android16-5-gb2a876903b49-ab14541642-4k | DISCOVERED |
| Technical SoC | SM8750 (assumed) | `SM8850` / `canoe` (SoC ID: 660) | VERIFIED |
| Commercial SoC Name | Snapdragon 8 Elite | `Snapdragon 8 Elite Gen 5` | VERIFIED |
| CPU Architecture | Oryon | `3rd-generation Qualcomm Oryon` (6 Perf @ 3.63GHz + 2 Prime @ 4.61GHz) | VERIFIED |
| GPU Subsystem | Adreno GPU | `Qualcomm Adreno 840` (Vulkan 1.4.0, GLES 3.2) | NOT_TESTED (Execution) |
| NPU Subsystem | Hexagon NPU | `Qualcomm Hexagon HTP V81` cDSP (QNN 2.37.4 libraries present) | NOT_TESTED (Execution) |
| Camera Devices | 3 cameras assumed | 5 devices discovered (all `LEVEL_3`) | VERIFIED |
| Rear Multi-Camera | Camera 0 (generic) | Camera 0 logical multi-camera combining physical IDs `["3", "2", "4"]` | VERIFIED |
| Front Camera | Camera 1 | 3280x2464, 3.23 mm, f/2.4, Orientation 270° | VERIFIED |
| Main Wide Physical | Camera 2 | 50 MP (8192x6144 full / 4096x3072 binned), 5.59 mm, f/1.88, OIS | VERIFIED |
| Ultra-Wide Physical | Camera 3 | 4096x3072, 2.31 mm, f/2.0 | VERIFIED |
| Telephoto Physical | Camera 4 | 4096x3072, 12.19 mm, f/2.85 (~70-75 mm eq) | VERIFIED |
| Direct HAL Ultra HDR | Supported | Disabled via `ro.camera.disableHeicUltraHDR=true` (Software pipeline handles HDR) | DISABLED_BY_HAL |
| IMU Sensor Model | Generic 200 Hz | STMicroelectronics `lsm6dsv` @ ~479.85 Hz (`CLOCK_BOOTTIME`) | VERIFIED |
| Zero-Copy Scanout | Surface 0-copy | Direct SurfaceFlinger scanout (0 copies) verified; NPU/GPU zero-copy path `NOT_TESTED` | PARTIAL |

---

## 3. Discovered OnePlus 15 Runtime Characteristics

Authoritative runtime profile artifacts located at:
- Directory: `profiles/runtime/OnePlus_15/20260925_000000/`
  - [`device_profile.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/device_profile.json)
  - [`camera_profile.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/camera_profile.json)
  - [`stream_profile.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/stream_profile.json)
  - [`dynamic_range_profile.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/dynamic_range_profile.json)
  - [`sensor_profile.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/sensor_profile.json)
  - [`verification.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/verification.json)
  - [`capture_test_results.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/capture_test_results.json)
  - [`memory_results.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/memory_results.json)
  - [`latency_results.json`](file:///workspace/raphael/profiles/runtime/OnePlus_15/20260925_000000/latency_results.json)
