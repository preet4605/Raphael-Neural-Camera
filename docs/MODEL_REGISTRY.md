# Model Registry & Lifecycle Management

## 1. Specification Invariants
In accordance with Rule 33, 34, and 35:
- Every model entry is immutable and strictly versioned (`ModelVersion(major, minor, patch)`).
- Every model registers its precise input/output tensor shapes, precision (`FP32`, `FP16`, `INT8`, `INT4`), file size, expected latency, memory budget, and designated fallback.

## 2. Catalog Taxonomy
| Model ID | Semantic Purpose | Precision | Size | Target Backend | Expected Latency | Fallback Model |
|---|---|---|---|---|---|---|
| `classical-baseline-isp-v1` | NEURAL_ISP | FP32 | 0 MB | XNNPACK_CPU | 28 ms | None (Ground Baseline) |
| `neural-isp-lite-v1` | NEURAL_ISP | INT8 | 14 MB | QNN_NPU / Vulkan | 42 ms | `classical-baseline-isp-v1` |
| `perception-motion-alignment-v1` | TEMPORAL_RECONSTRUCTION | INT8 | 6 MB | QNN_NPU / Vulkan | 12 ms | Classical Optical Flow |
| `omnineural-4b-mobile-v1` | SEMANTIC_DIRECTOR | INT4 | 2.2 GB | QNN_NPU / Vulkan | 350 ms | Classical Heuristics |
| `flux-klein-4b-studio-v1` | GENERATIVE (Studio Only) | INT4 | 2.4 GB | QNN_NPU / Vulkan | 2800 ms | None (User-triggered only) |
