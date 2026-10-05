# Classical Temporal Burst Pipeline

> **Status: implemented and validated on synthetic data only.** It has never processed a real capture. It is not wired to
> the camera UI. Gate 3 (blind comparison against the stock camera and GCam) has not started, and
> `CLASSICAL_MERGE_ADVANTAGE_PROVEN` is `FALSE`.

This is the classical baseline the neural work will be measured against: no neural model is involved.

## What exists (`:neural-isp`, package `com.neuralcamera.isp.temporal`)

| Piece | File | Role |
|---|---|---|
| Planes, radiometry, noise model | `Planes.kt` | `U16Plane` (sensor DN), `Radiometry` (black/white level and exposure gain relative to the reference), `NoiseModel` (Poisson-Gaussian, `var = S*x + O` in normalized units, the DNG/Android noise-profile convention) |
| Tile alignment | `TileAligner.kt` | Coarse-to-fine tile matching (16 px tiles, 8 px stride, up to 4 pyramid levels), integer search then Lucas-Kanade sub-pixel refinement at full resolution; unmatched tiles take the median of reliable neighbours |
| Merge | `TemporalMerger.kt` | Per tile and alternate frame: bicubic warp, 3x3-smoothed difference normalized by expected noise plus a gradient-proportional alignment tolerance, Tukey-style rejection, tile-level gating, inverse-variance weighting across exposures, clipped samples excluded, Hann-window blending of overlapping tiles |
| RAW Bayer wrapper | `BayerMerge.kt` | Splits the mosaic into four colour planes, aligns once on a luma-like proxy, merges every plane with the same motion field. The CFA structure is preserved exactly. No demosaic |
| Analysis | `FrameAnalysis.kt` | Global noise sigma (median of Immerkaer responses), noise-robust sharpness for choosing the reference |
| 8-bit luma baseline | `BaselineImagePipeline.kt` | Replaces the old blind per-pixel mean: sharpest frame as reference, stride-aware luma extraction, homoscedastic noise estimated from the reference. Luma only (grayscale output) |
| DNG reading | `dng/` | `TiffFile`/`DngReader` (CFA pattern, black/white levels, NoiseProfile, ActiveArea, AsShotNeutral, EXIF exposure/ISO) and a quick-look `DngPreview` |

Design points that follow the project rules:

- **No blind averaging.** Every alternate pixel is compared with the reference after alignment; disagreement (moving
  objects, occlusion, residual misalignment) removes it from the average.
- **Evidence-constrained.** Output is a weighted mean of captured samples; nothing is synthesized.
- **Exposure-aware.** Frames of different exposure are mapped to the reference's linear scale; clipped samples carry no
  weight, so clipped highlights are filled from shorter exposures; longer exposures count more (lower noise).
- **Quality signals.** `FrameMergeStats` (mean weight, rejected tiles, residual per alternate frame) is returned for the
  quality engine.
- **Deterministic.** Tile rows run in two non-overlapping phases, so the result is bit-identical for any thread count.

## Evidence so far (synthetic, host JVM, 256x192, 8 frames)

Ground truth is known, so PSNR against it is exact. Noise: shot 0.002, read 0.0001 (about 30 dB for a single frame).
These numbers come from unit tests and a scratch run on this repository's synthetic scenes; they say the algorithm does
what it claims on that data and nothing about real captures.

| Scenario | Single frame | Blind mean | Merge |
|---|---|---|---|
| Static scene | 30.2 dB | n/a | 38.9 dB (+8.7, theory +9.0) |
| Hand shake (random +-3 px) | 30.2 dB | 26.4 dB | 40.3 dB |
| Moving block (object region) | 28.7 dB | 14.1 dB (ghosting) | 33.5 dB |

Also tested: alignment recovers known sub-pixel shifts within 0.15 px under noise; a short-exposure alternate recovers a
highlight that clips in the reference (reads ~2.0 vs clipped 1.0); clipped alternate samples never contaminate the
output; an unrelated frame is rejected instead of blended; RAW Bayer merge denoises every colour channel by 5 dB or more
without cross-talk; the result is independent of the thread count.

`tools/burst_merge/` runs the Bayer merge offline on a folder of DNG frames (for example a Gate 1 run's output) and
writes a linear merged mosaic, previews and a report with a global noise estimate before and after. `selftest.sh` checks
it end to end on a synthetic burst.

## Known limits

- **Never run on real data.** Real noise (fixed pattern, lens shading, defective pixels, rolling shutter, optical
  stabilization motion) is not modelled. Real bursts may need parameter changes.
- **Translation per tile only.** Rotation, strong parallax and subject motion between frames become high residual and
  are rejected (less denoising there), not modelled.
- **No IMU prior** yet; the search range is the pyramid's: `coarseRadius * 2^(levels-1)` pixels (about +-32 px for a
  12 MP frame, +-8 px for a 256x192 test image).
- **Speed.** About 0.5 s per megapixel per alternate frame on a 4-core x86 host JVM (a 12 MP burst of 8 frames would
  take roughly 45 s there). It is an offline pipeline; a native/SIMD or GPU implementation is future work.
- **Memory.** The merge holds the frames plus about 12 bytes per pixel of working arrays: workable at 12 MP, not at the
  50 MP full-resolution size without banded processing.
- **Not an ISP.** No demosaic, colour matrix, tone mapping, sharpening, or HDR rendering; the 8-bit baseline is luma
  only. Previews from the offline tool are a bilinear demosaic with white balance and an sRGB curve.
- **Not wired to capture.** The camera UI's burst path still returns no frames.

## Next steps toward Gate 3

1. Run the Gate 1 recorder on the device and feed its DNGs to `tools/burst_merge`; inspect real results and tune.
2. Colour pipeline: demosaic, white balance, colour matrix, tone mapping, highlight/shadow handling, gain-map HDR.
3. Wire real burst capture into the app and encode real JPEG/DNG/HEIF outputs.
4. IMU-aided motion prior and rotation-aware alignment.
5. Build the 30-scene dataset and the blind A/B protocol (`docs/PROOF_GATES.md`, Gate 3).
