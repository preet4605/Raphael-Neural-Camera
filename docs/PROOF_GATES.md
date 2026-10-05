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

### How Gate 1 is run and judged

- **Recorder**: `RawBurstRecorder` (`:camera-core`) runs inside the debug-only `Gate1ProbeActivity`. It opens a back
  camera that advertises RAW_SENSOR 8192x6144 (preferring a physical camera over the logical multi-camera), requests the
  size in whichever sensor pixel mode advertises it (DEFAULT, else MAXIMUM_RESOLUTION, in which case the session holds
  only the RAW stream and has no preview), and captures an 8-request burst with `acquireNextImage()` and
  `maxImages == 8`, so nothing is silently dropped. A frame that does not arrive is recorded as dropped; frames are never
  duplicated, substituted or synthesized.
- **Persisted outputs** (per run): `gate1_report.json` (raw per-frame records and camera characteristics), `frames.csv`,
  and one DNG per frame written with `DngCreator` and fsynced. A run writes about 800 MB.
- **Per-frame record**: request order, frame number, capture (sensor) timestamp, image timestamp, image and result arrival
  times (`elapsedRealtimeNanos`), exposure time, ISO, dimensions, format, row/pixel stride, plane size, SHA-256 of the
  pixel bytes, DNG file and size, dropped status and reason.
- **Run it**: install the debug build, then `tools/proof/run_gate1_adb.sh` (three fresh processes, pulls the evidence,
  runs the checker), or launch `Gate1ProbeActivity` three times, force-stopping the app between runs, and run
  `python3 tools/proof/check_gate1.py <pulled gate1 directory>`.
- **Judge**: only `tools/proof/check_gate1.py`. It ignores the app's own evaluation, re-derives every criterion from the
  raw records, and parses the DNG files themselves (raw IFD of exactly 8192x6144, uncompressed 16-bit CFA, DNGVersion
  present, data length 8192*6144*2) and hashes their raw pixel data, so duplicates are detected from the stored outputs.
  `RAW_BURST_PROVEN=TRUE` needs at least three independent runs (distinct run ids and process starts) on one device, all
  passing, with no failing run among those provided.
- **Ordinary app**: the report records `isSystemApp`, the uid and the granted permissions (CAMERA only is expected); the
  checker requires a non-system app with uid >= 10000. Whether the OEM exposes the full-resolution RAW stream to
  third-party apps is exactly what this gate tests; a failure here is a valid result.

Until a device run produces evidence that passes the checker, `RAW_BURST_PROVEN` stays `FALSE`.

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

### How Gate 2 is run and judged

- **Workload**: `denoise-tiny-v1`, an untrained deterministic residual conv net (committed weights and ONNX artifacts, see
  `tools/model_gen/gen_denoise_tiny.py`). It is a validation network, not a denoiser. Its output differs from its input
  by about 24 dB PSNR, so a pass-through cannot reach the 35 dB threshold.
- **Reference**: a pure-Kotlin FP32 implementation (`CpuReferenceBackend`, double accumulation) on the same
  deterministic input. The ONNX FP32 graph on the ONNX Runtime CPU EP matches it to under 1e-5 on a host.
- **Candidates**: `htp-fp32`, `htp-qdq-a16w8`, `htp-qdq-a8w8` through ONNX Runtime's QNN execution provider with the
  QNN runtime bundled from Maven (`com.qualcomm.qti:qnn-runtime`), CPU EP fallback disabled and
  `offload_graph_io_quantization=0` so no node may run on the CPU. `ort-cpu-fp32` is a control and can never prove HTP.
- **Attribution** (all must hold, re-derived by the checker from raw data): the session was created with CPU EP fallback
  disabled and `backend_type=htp`; the ONNX Runtime profile shows every node event under `QNNExecutionProvider` and none
  under any other provider; a `libQnnHtp*` library is mapped in the process.
- **Run it**: install the debug build, then `tools/proof/run_gate2_adb.sh` (three fresh processes, pulls the reports,
  runs the checker), or launch `Gate2ProbeActivity` three times, force-stopping the app between runs, and run
  `python3 tools/proof/check_gate2.py <pulled gate2 directory>`.
- **Judge**: only `tools/proof/check_gate2.py`. It ignores the app's own verdict, uses fixed thresholds, and prints
  `HTP_INFERENCE_PROVEN=TRUE` only when one variant has at least three independent passing runs (distinct run ids and
  process starts) on one device with the same input and reference hashes, and no failing report of that variant.
- **Not measurable by an unprivileged app**: GPU utilization (recorded as unavailable with the reason). Thermal status
  and headroom, battery temperature, process CPU time and PSS are sampled every 10 iterations.
- **Privacy**: ONNX Runtime's AAR ships a telemetry client and network permissions. The app removes the telemetry
  provider and the `INTERNET`/`ACCESS_NETWORK_STATE` permissions in its manifest and calls `setTelemetry(false)`.

Until a device run produces reports that pass the checker, `HTP_INFERENCE_PROVEN` stays `FALSE`.

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
