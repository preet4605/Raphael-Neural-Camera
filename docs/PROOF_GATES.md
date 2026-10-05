# Empirical Proof Gates

Nothing is claimed until a gate's evidence exists. A library being present, a model compiling, or an
API being available is never execution proof. Stop at every gate and report evidence before
advancing a phase.

## Global proof flags

| Flag | State |
|---|---|
| `RAW_BURST_PROVEN` | `FALSE` |
| `HTP_INFERENCE_PROVEN` | `FALSE` |
| `CLASSICAL_MERGE_ADVANTAGE_PROVEN` | `FALSE` |
| `NEURAL_ISP_PROVEN` | `FALSE` |
| `PRODUCT_ADVANTAGE_PROVEN` | `FALSE` |
| `FULL_PIPELINE_PROVEN` | `FALSE` |

The last three may become `TRUE` only after all of the first three are `TRUE`.

## Gate 1: full-resolution third-party RAW burst

Fixed test: main rear camera, `RAW_SENSOR`, 8192x6144, at least 8 consecutive frames, no duplicates,
no interpolation, ordinary non-system app, no root or OEM-private privileges.

Per frame record: sequence/order, capture timestamp, arrival timestamp, exposure time, ISO,
dimensions, format, dropped-frame status.

Pass: 8/8 valid full-resolution RAW frames, zero drops, zero duplicates, valid metadata, monotonic
timestamps, app stays alive, outputs inspectable, reproducible for 3 runs. Then `RAW_BURST_PROVEN=TRUE`.

## Gate 2: real HTP inference

Test: small deterministic denoise model with fixed input/output, deterministic pre/post-processing
and an FP32 reference path.

Pass requires:
- actual in-process execution with explicit HTP/NPU backend attribution, and no CPU fallback for the measured graph
- numerical agreement with FP32 (PSNR >= 35 dB), no NaN/Inf, no catastrophic clipping
- 300 consecutive inferences with p95 <= 100 ms/inference
- measured cold/warm latency, median, p95, worst, throughput, temperature, thermal status, memory, CPU/GPU use and HTP evidence
- 3 independent runs

Then `HTP_INFERENCE_PROVEN=TRUE`.

## Gate 3: classical burst merge advantage

Dataset: 10 low-light, 10 HDR and 10 motion scenes (30 total). Baselines: OnePlus stock camera and
GCam/SGCAM, with versions and configuration frozen before evaluation. Evaluation is blind A/B with
anonymous IDs and randomized presentation.

Measure: noise, detail, highlight clipping, shadow visibility, alignment quality, motion ghosting,
smear, visual artifacts.

Pass requires: complete dataset, no selective reporting, no catastrophic failures, measurable
improvement over both stock and GCam/SGCAM, no unacceptable HDR or motion regression, and blind
preference consistent with the objective metrics. Comparable-but-not-better is
`MERGE_ADVANTAGE=NOT_PROVEN`.
