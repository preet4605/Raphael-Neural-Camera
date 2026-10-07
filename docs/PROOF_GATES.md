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
  QNN runtime bundled from Maven (`com.qualcomm.qti:qnn-runtime` **2.42.0**, pulled transitively by
  `onnxruntime-android-qnn:1.29.0`; the OnePlus 15 ships QNN **2.37.4**, so this pairing is UNVERIFIED until a run passes), CPU EP fallback disabled and
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

### Gate 3 status

Not started. BLOCKED on: Gate 1 PASS (the merge must run on real RAW bursts), a 30-scene dataset, frozen stock/GCam
baselines, and blind A/B + metric tooling (none exists). The merge core and RAW front end are validated on synthetic data
only. `CLASSICAL_MERGE_ADVANTAGE_PROVEN` stays `FALSE`.

---

## Physical proof procedures (to be run by a person with the phone)

Nothing below has been run. Results must come from the checkers; do not edit flags by hand. The commands use plain
`adb`, JDK 17 and the Android SDK, so they run unchanged in Mobile Harness Plus or on any workstation that provides those.

### 0. Common setup (once per session)

```bash
# Device identity (must match: OnePlus 15, CPH2745, canoe/SM8850, Android 16 / API 36)
adb shell getprop ro.product.model; adb shell getprop ro.board.platform; adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint            # record verbatim in the evidence folder

# Build and install the debug APK (applicationId com.neuralcamera.app.debug)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.neuralcamera.app.debug android.permission.CAMERA

# Thermal starting point: let the phone cool, unplugged from fast charging, screen on
adb shell dumpsys thermalservice | head -20      # record; start only at status 0 (NONE)
```

### Gate 1: full-resolution RAW burst (sets `RAW_BURST_PROVEN`)

```bash
# Three fresh-process runs on the main rear camera; ~800 MB per run on device and host
tools/proof/run_gate1_adb.sh gate1_evidence_$(date +%Y%m%d)        # optional 2nd arg: camera id
# Re-judge any pulled folder independently:
python3 tools/proof/check_gate1.py gate1_evidence_YYYYMMDD
```

Keep the whole pulled folder (reports, `frames.csv`, every DNG) plus the `getprop` output. PASS only if the checker
prints `RAW_BURST_PROVEN=TRUE`. A FAIL that shows the OEM does not expose 8192x6144 RAW to third-party apps is a valid,
reportable result; do not retry with other sizes and call that Gate 1.

### Gate 2: real HTP inference (sets `HTP_INFERENCE_PROVEN`)

```bash
tools/proof/run_gate2_adb.sh gate2_evidence_$(date +%Y%m%d)
python3 tools/proof/check_gate2.py gate2_evidence_YYYYMMDD
# QNN pairing evidence (the app bundles QNN 2.42.0 from Maven; the device ships 2.37.4):
grep -rho '"/[^"]*libQnn[^"]*"' gate2_evidence_YYYYMMDD | sort -u   # mappedLibraryPaths: app copy (/data/app/...) or vendor (/vendor/...)
adb shell 'ls -l /vendor/lib64/libQnn* /vendor/dsp/cdsp/ 2>/dev/null'      # device-side QNN files, for the record
```

PASS only if the checker prints `HTP_INFERENCE_PROVEN=TRUE` for a variant (three independent runs, whole graph on
`QNNExecutionProvider`, `libQnnHtp*` mapped, PSNR >= 35 dB vs FP32, p95 <= 100 ms over 300 runs). If session creation
fails, the report is the evidence that the 2.42.0 bundle does not work on this firmware; record it, do not swap QNN
versions silently. Changing the bundled QNN version is a separate, explicit decision and resets the gate.

### Gate 3: classical merge advantage (sets `CLASSICAL_MERGE_ADVANTAGE_PROVEN`) — BLOCKED

Preconditions: Gate 1 PASS; a blind A/B tool and metric scripts exist and are committed **before** capture.

1. Freeze baselines and record their versions:
   `adb shell dumpsys package com.oneplus.camera | grep versionName` and the same for the GCam/SGCAM package; record its
   config file. No changes after this point.
2. Capture 30 scenes (10 low light, 10 HDR, 10 motion). Per scene, without moving the tripod/handheld position between
   apps: stock shot, GCam shot, and one Gate 1 RAW burst (`adb shell am start -n
   com.neuralcamera.app.debug/com.neuralcamera.app.proof.Gate1ProbeActivity --ez autorun true`). Log every attempt;
   a failed capture stays in the dataset as a failure.
3. Process each burst offline with the committed tool:
   `./gradlew -p tools/burst_merge run --args="--input <burst dir> --output <out dir>"`.
4. Score all 30 scenes with the frozen metric scripts (noise, detail, clipping, shadow visibility, alignment, ghosting,
   smear, artifacts) and run the blind A/B with anonymous ids and randomized order.
5. Report every scene. PASS needs measurable improvement over both baselines with no catastrophic failure and blind
   preference consistent with the metrics; comparable-but-not-better is `NOT_PROVEN`.

### Order and stop rule

Run Gate 1 and Gate 2 (independent of each other), report, and stop. Gate 3 starts only after Gate 1 passes and its
tooling is reviewed. `NEURAL_ISP_PROVEN`, `PRODUCT_ADVANTAGE_PROVEN` and `FULL_PIPELINE_PROVEN` stay `FALSE` until all
three gates pass.
