# Camera Acquisition & Processing Pipeline

## 1. Zero-Copy Pipeline Graph
The camera acquisition pipeline separates sensor frame acquisition from presentation and inference:

```
Camera Sensor (Camera2 HAL)
      │
      ▼
ImageReader / Surface (RAW_SENSOR / YUV_420_888)
      │
      ▼
FrameSynchronizer (Hardware Timestamps + IMU Gyro/Accel + Metadata)
      │
      ▼
BoundedRingFrameRepository (Temporal Circular Ring Buffer)
      │
      ▼
Capture Intelligence (UniversalCapturePlanner: Frame Count & Exposure)
      │
      ▼
PipelineScheduler (Thermal + Memory + Latency Evaluation)
      │
      ▼
Neural ISP / Classical Baseline ISP (Multi-frame Temporal Fusion)
      │
      ▼
RealityGuard (Hallucination Detection & Ground-Truth Blending)
      │
      ▼
Non-Destructive Storage (Original Sensor Plane + Master Photograph)
```

## 2. Shooting Modes
- **AUTO**: Intelligent exposure balancing, automatic temporal frame depth (1 to 8 frames) based on scene lux and motion vectors.
- **PRO**: Unconstrained manual controls (ISO 50-25600, shutter 1/32000s to 30s), uncompressed RAW10/12 capture with neural reconstruction active.
- **MASTER**: Deep temporal burst capture (up to 16 frames), exposure bracketing, maximum dynamic range preservation, and refined photographic tone curve.
- **AUTHENTIC**: Ground-truth prioritized rendering with conservative reconstruction, zero synthetic texture injection, and natural chromatic fidelity.

## 3. Reality Guard Invariant
High-confidence sensor regions are never altered by synthetic neural generation. If neural reconstruction deviates from ground truth beyond threshold limits, Reality Guard automatically reverts or blends back original sensor pixels.
