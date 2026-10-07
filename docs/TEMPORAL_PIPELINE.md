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
| 8-bit YUV baseline | `BaselineImagePipeline.kt` | Replaces the old blind per-pixel mean: sharpest frame as reference, stride-aware luma extraction, homoscedastic noise estimated from the reference. Luma is merged; colour comes from the reference frame's Cb/Cr planes (bilinear upsample, BT.601 full range) and is **not** temporally merged, so chroma noise is not reduced. Frames without chroma planes give grayscale and `isColour = false` |
| Colour baseline | `color/` | Malvar-He-Cutler demosaic (sensor samples pass through unchanged), camera-to-sRGB transform from DNG ForwardMatrix1 or ColorMatrix1 (Bradford to D50) with white-balance-only fallback that is reported as such, hue-preserving highlight shoulder, sRGB encode. No lens shading, local tone mapping, sharpening or gamut mapping |
| Encoders | `encode/` | `JpegEncoder`: baseline JPEG (grayscale or YCbCr 4:4:4), EXIF orientation/exposure/ISO, pure Kotlin. `DngWriter`: linear uncompressed 16-bit CFA DNG from a merged mosaic; refuses to write without colour calibration and does not write a NoiseProfile (the source's would overstate merged noise) |
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
writes a linear merged mosaic (`merged.pgm`, plus `merged.dng` when the source DNGs carry colour calibration), previews (including `*_color.png` and `merged_color.jpg` from the colour baseline) and a report with a global noise estimate before and after. Before the merge it fixes hot/dead pixels in every frame
(dynamic, noise-profile threshold); after it, it applies the lens shading gain map the DNG carries (OpcodeList2
GainMap, as DngCreator writes it when the capture enabled the shading map) to the merged mosaic and the reference.
Shading comes after the merge because the noise profile describes unshaded sensor data. `selftest.sh` checks it end to
end on a synthetic burst with vignetting, an embedded gain map and fixed defective pixels; it needs `rawpy` and
`Pillow`.

## Known limits

- **Never run on real data.** Lens shading and defective pixels are corrected only as synthesized; fixed-pattern
  noise, rolling shutter and optical stabilization motion are not modelled. Whether the OnePlus 15 reports a shading
  map, and what its DNG gain maps look like, is NOT_TESTED. Real bursts may need parameter changes.
- **Gyro seeding is ready but unfed.** `TileAligner.align` and both merges take optional per-frame `AlignmentSeed`s
  (they add candidates; the zero-motion search still runs), and `GyroAlignmentSeed` turns gyro samples into a seed.
  Nothing records gyro samples next to a burst yet, and the gyro-to-image axis mapping must be measured on the device
  (#11 D7); until it is, seeds have confidence 0 and are not used. Unseeded range: about +-32 px on a 4-level pyramid.
- **Translation per tile only.** Rotation, strong parallax and subject motion between frames become high residual and
  are rejected (less denoising there), not modelled.
- **No IMU prior** yet; the search range is the pyramid's: `coarseRadius * 2^(levels-1)` pixels (about +-32 px for a
  12 MP frame, +-8 px for a 256x192 test image).
- **Speed.** About 0.5 s per megapixel per alternate frame on a 4-core x86 host JVM (a 12 MP burst of 8 frames would
  take roughly 45 s there). It is an offline pipeline; a native/SIMD or GPU implementation is future work.
- **Memory.** The merge holds the frames plus about 12 bytes per pixel of working arrays: workable at 12 MP, not at the
  50 MP full-resolution size without banded processing.
- **Not a full ISP.** `color/` is a conservative baseline (demosaic, colour matrix, global tone curve). No lens shading, sharpening, local tone mapping or HDR rendering; the 8-bit camera baseline merges luma, and merges 4:2:0 chroma along the luma motion with its own difference test (synthetic tests only).
- **Capture is wired, but to YUV, and untested on a device.** `RealCamera2Controller.triggerBurstCapture` now captures a real `captureBurst` into a YUV_420_888 reader (largest size up to 4.2 MP), copies and closes each image on arrival, pairs images with results by `SENSOR_TIMESTAMP` (`BurstCollector`, JVM-tested), and returns only frames actually captured (failed or lost frames are absent; the count is capped by a 100 MB copy budget). The HAL has already denoised these frames, so they are not the RAW burst the project targets; the app still feeds them to the luma baseline. RAW capture into the Bayer merge waits on Gate 1. The code compiles against the Android API but has never run on hardware. Exposure bracketing is not wired (a bracket request returns no frames).

## Next steps toward Gate 3

1. Run the Gate 1 recorder on the device and feed its DNGs to `tools/burst_merge`; inspect real results and tune.
2. Colour pipeline: baseline done (`color/`, synthetic tests only; never compared against real sensor colour). Lens shading is applied in `tools/burst_merge` (synthetic tests only). Still missing: lens shading on the app path, local tone mapping, highlight/shadow handling, gain-map HDR, real-device colour validation.
3. Burst capture is wired (YUV) and the app saves real JPEGs (colour when the frames carry chroma planes). Still missing: RAW capture feeding the Bayer merge and an on-device DNG save (Gate 1), colour JPEG from the app path, HEIF/Ultra HDR, gain-map HDR.
4. IMU-aided motion prior and rotation-aware alignment.
5. Build the 30-scene dataset and the blind A/B protocol (`docs/PROOF_GATES.md`, Gate 3).
