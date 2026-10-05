# Neural Runtime & Compute Scheduling

> **Status: contracts and scheduler only. No backend is implemented.** There is no QNN, Vulkan or XNNPACK integration and no verified model. `StandardInferenceRuntime` throws `BackendUnavailableException` when no backend executed a request, and the scheduler falls back to the classical ISP for any model that is not `VERIFIED`. Backend preference below is a design order; a backend counts as usable only after a run with in-process backend attribution (Gate 2 in [`PROOF_GATES.md`](PROOF_GATES.md)).

## 1. Hardware Delegation Hierarchy
The runtime detects, initializes, and benchmarks available execution backends in order of hardware efficiency:
1. **Qualcomm QNN NPU (Hexagon HTP)**: Planned INT8/INT4 acceleration. Latency and thermal behavior are unmeasured; HTP initialization and execution are NOT_TESTED.
2. **Vulkan GPU (Adreno / Mali)**: Planned FP16 tensor shaders for parallel spatial convolutions. Model execution is NOT_TESTED.
3. **XNNPACK CPU**: Planned multi-threaded ARM NEON fallback. Not integrated yet; the CPU reference backend is the first Phase 2 deliverable.

## 2. Dynamic Throttling & Budgeting
The `AdaptivePipelineScheduler` continuously samples:
- **Thermal Level**: `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`.
  - When thermal level reaches `SEVERE`, the scheduler immediately cuts burst frame count from 16 to 4 and delegates to low-power classical pipelines.
- **Memory Pressure**: Bounded model residency and intermediate buffer ceilings. If model allocation exceeds budget, the system falls back to classical processing rather than triggering an OOM crash.
- **Target FPS Latency**: Models exceeding 33ms during real-time 30fps streaming are decoupled from the preview hot path.
