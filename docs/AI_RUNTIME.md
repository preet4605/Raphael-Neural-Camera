# Neural Runtime & Compute Scheduling

> **Status: implemented, never run on the device.** There are two backends: `CpuReferenceBackend` (pure-Kotlin FP32 golden reference) and `OrtBackend` (ONNX Runtime CPU EP, and the QNN EP on the Hexagon HTP). Only one validation network (`denoise-tiny-v1`) has artifacts. No Vulkan or XNNPACK backend exists. `StandardInferenceRuntime` throws `BackendUnavailableException` when no backend executed a request, the scheduler falls back to the classical ISP for any model that is not `VERIFIED`, and a backend counts as usable only after a run with in-process backend attribution (Gate 2 in [`PROOF_GATES.md`](PROOF_GATES.md)).

## 1. Hardware Delegation Hierarchy
The runtime detects, initializes, and benchmarks available execution backends in order of hardware efficiency:
1. **Qualcomm QNN NPU (Hexagon HTP)**: Reached through ONNX Runtime's QNN EP with the QNN runtime bundled from Maven. Latency and thermal behavior are unmeasured; HTP initialization and execution are NOT_TESTED until the Gate 2 evidence passes `tools/proof/check_gate2.py`.
2. **Vulkan GPU (Adreno / Mali)**: Planned FP16 tensor shaders for parallel spatial convolutions. Model execution is NOT_TESTED.
3. **CPU**: `OrtBackend` on the ONNX Runtime CPU EP is the optimized CPU path (unproven on the device); `CpuReferenceBackend` is the always-available golden reference and final fallback. XNNPACK is not integrated.

## 2. Dynamic Throttling & Budgeting
The `AdaptivePipelineScheduler` continuously samples:
- **Thermal Level**: `NONE`, `LIGHT`, `MODERATE`, `SEVERE`, `CRITICAL`.
  - When thermal level reaches `SEVERE`, the scheduler immediately cuts burst frame count from 16 to 4 and delegates to low-power classical pipelines.
- **Memory Pressure**: Bounded model residency and intermediate buffer ceilings. If model allocation exceeds budget, the system falls back to classical processing rather than triggering an OOM crash.
- **Target FPS Latency**: Models exceeding 33ms during real-time 30fps streaming are decoupled from the preview hot path.
