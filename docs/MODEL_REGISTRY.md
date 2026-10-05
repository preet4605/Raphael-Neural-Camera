# Model Registry & Lifecycle Management

## 1. Specification Invariants
In accordance with Rule 33, 34, and 35:
- Every model entry is immutable and strictly versioned (`ModelVersion(major, minor, patch)`).
- Every model registers its precise input/output tensor shapes, precision (`FP32`, `FP16`, `INT8`, `INT4`), file size, expected latency, memory budget, and designated fallback.

## 2. Catalog Taxonomy

Every entry is a placeholder: no model artifact exists in the repository and nothing has been executed on any backend. All entries are `UNVERIFIED`, with size 0, license `UNSPECIFIED`, and latency/memory/thermal cost unmeasured (null). The scheduler never runs an unverified model on an accelerator; it falls back to the classical baseline. Precision and target backends are design intent only.

| Model ID | Semantic Purpose | Planned Precision | Planned Backend | State | Fallback Model |
|---|---|---|---|---|---|
| `classical-baseline-isp-v1` | NEURAL_ISP | FP32 | XNNPACK_CPU | UNVERIFIED | None (Ground Baseline) |
| `neural-isp-lite-v1` | NEURAL_ISP | INT8 | QNN_NPU / Vulkan | UNVERIFIED | `classical-baseline-isp-v1` |
| `perception-motion-alignment-v1` | TEMPORAL_RECONSTRUCTION | INT8 | QNN_NPU / Vulkan | UNVERIFIED | None declared |
| `omnineural-4b-mobile-v1` | SEMANTIC_DIRECTOR (intended role only; model details unverified; outside the pixel hot path) | INT4 | QNN_NPU / Vulkan | UNVERIFIED | None declared |
| `flux-klein-4b-studio-v1` | GENERATIVE (AI Studio only) | INT4 | QNN_NPU / Vulkan | UNVERIFIED | None (User-triggered only) |
