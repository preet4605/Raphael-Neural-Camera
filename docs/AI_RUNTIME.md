# Neural Runtime & Compute Scheduling

## 1. Hardware Delegation Hierarchy
The runtime detects, initializes, and benchmarks available execution backends in order of hardware efficiency:
1. **Qualcomm QNN NPU (Hexagon HTP)**: Native INT8/INT4 acceleration, sub-50ms latency, high thermal efficiency.
2. **Vulkan 1.3 GPU (Adreno / Mali)**: FP16 tensor shaders for parallel spatial convolutions.
3. **XNNPACK CPU**: Multi-threaded ARM NEON fallback for guaranteed execution on any Android device.

## 2. Dynamic Throttling & Budgeting
The `AdaptivePipelineScheduler` continuously samples:
- **Thermal Level**: `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`.
  - When thermal level reaches `SEVERE`, the scheduler immediately cuts burst frame count from 16 to 4 and delegates to low-power classical pipelines.
- **Memory Pressure**: Bounded model residency and intermediate buffer ceilings. If model allocation exceeds budget, the system falls back to classical processing rather than triggering an OOM crash.
- **Target FPS Latency**: Models exceeding 33ms during real-time 30fps streaming are decoupled from the preview hot path.
