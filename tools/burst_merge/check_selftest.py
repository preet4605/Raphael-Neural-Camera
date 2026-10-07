#!/usr/bin/env python3
"""Compares tool output to the synthetic ground truth: merged.pgm must beat the single reference frame, with lens
shading and the fixed hot/dead pixels corrected."""
import json
import pathlib
import sys

import numpy as np

burst, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
truth = np.load(burst / "truth.npy").astype(np.float64)
report = json.loads((out / "merge_report.json").read_text())


def read_pgm(path):
    data = path.read_bytes()
    magic, dims, maxval, rest = data.split(b"\n", 3)
    w, h = map(int, dims.split())
    return np.frombuffer(rest, dtype=">u2").reshape(h, w).astype(np.float64) / 65535.0


def psnr(a, b):
    mse = np.mean((a[16:-16, 16:-16] - b[16:-16, 16:-16]) ** 2)
    return 10 * np.log10(1.0 / mse)


merged = read_pgm(out / "merged.pgm")
ref_name = next(f["file"] for f in report["frames"] if f["isReference"])
assert ref_name == "frame_00.dng", "the truth is frame 0's geometry, so the run must use --reference 0"
print("tool ratio of green noise sigma (merged/reference):", report["noiseSigmaGreen"]["ratio"])
p = psnr(merged, truth)
print(f"PSNR merged vs truth: {p:.2f} dB")
assert report["noiseSigmaGreen"]["ratio"] < 0.7, "merged noise should be clearly lower than the reference's"
assert p > 33.0, "merged mosaic should be a good estimate of the truth"

fe = report["frontEnd"]
assert str(fe["lensShading"]).startswith("applied"), fe
assert all(n >= len(np.load(burst / "defects.npy")) for n in fe["defectCorrection"]["pixelsPerFrame"]), fe
defects = np.load(burst / "defects.npy")
worst = max(abs(merged[r, c] - truth[r, c]) for r, c in defects)
print(f"defective pixels: worst abs error {worst:.4f}")
assert worst < 0.05, "hot/dead pixels must be corrected"
# Shading: the corners (strongest vignetting) must be as close to the truth as the centre.
def region_bias(ys, xs):
    return float(np.mean(merged[ys, xs] - truth[ys, xs]))
bias_corner = region_bias(slice(16, 64), slice(16, 64))
bias_centre = region_bias(slice(merged.shape[0] // 2 - 24, merged.shape[0] // 2 + 24), slice(merged.shape[1] // 2 - 24, merged.shape[1] // 2 + 24))
print(f"mean bias: corner {bias_corner:+.4f}, centre {bias_centre:+.4f}")
assert abs(bias_corner) < 0.005 and abs(bias_centre) < 0.005, "lens shading must be corrected"

# Encoders: validated with independent decoders (libraw via rawpy, Pillow), not with the code that wrote the files.
import rawpy
from PIL import Image

assert report["mergedDng"]["file"] == "merged.dng", report["mergedDng"]
with rawpy.imread(str(out / "merged.dng")) as raw:
    dn = raw.raw_image.astype(np.float64)
    black = np.array(raw.black_level_per_channel, dtype=np.float64)
    white = float(raw.white_level)
    assert raw.raw_pattern.tolist() == [[0, 1], [3, 2]], raw.raw_pattern  # RGGB
    assert dn.shape == merged.shape, (dn.shape, merged.shape)
    norm = (dn - black.mean()) / (white - black.mean())
    err = np.abs(norm - merged)
    print(f"merged.dng (libraw) vs merged.pgm: max abs diff {err.max():.5f}")
    assert err.max() < 3.0 / (white - black.mean()), "DNG pixels must match the merged mosaic"
    rgb = raw.postprocess(use_camera_wb=True, no_auto_bright=True, output_bps=8)
    assert rgb.shape[2] == 3 and rgb.mean() > 5, "libraw could not develop the DNG"
jpg = Image.open(out / "merged_color.jpg")
jpg.load()
assert jpg.format == "JPEG" and jpg.mode == "RGB" and jpg.size == (merged.shape[1], merged.shape[0]), (jpg.format, jpg.mode, jpg.size)
png = np.asarray(Image.open(out / "merged_color.png").convert("RGB"), dtype=np.float64)
jp = np.asarray(jpg, dtype=np.float64)
if png.shape == jp.shape:
    mse = np.mean((png - jp) ** 2)
    print(f"JPEG vs PNG render PSNR: {10 * np.log10(255 ** 2 / max(mse, 1e-9)):.1f} dB")
    assert mse < 40, "JPEG should closely match the 8-bit render"
# Display P3 JPEG: Pillow (LittleCMS) must read its ICC profile and, converted to sRGB, match the sRGB render.
from io import BytesIO
from PIL import ImageCms
p3 = Image.open(out / "merged_color_p3.jpg")
icc = p3.info.get("icc_profile")
assert icc, "merged_color_p3.jpg has no ICC profile"
prof = ImageCms.ImageCmsProfile(BytesIO(icc))
assert "Display P3" in ImageCms.getProfileDescription(prof), ImageCms.getProfileDescription(prof)
as_srgb = np.asarray(ImageCms.profileToProfile(p3, prof, ImageCms.createProfile("sRGB"), outputMode="RGB"), dtype=np.float64)
# Compare where both renders are plain colour-space changes: below the highlight shoulder (applied to the brightest
# channel, which differs between primaries) and away from sRGB gamut mapping (min channel 0). The run uses --exposure 0.4 for that.
lin = np.where(jp <= 0.04045 * 255, jp / 255 / 12.92, ((jp / 255 + 0.055) / 1.055) ** 2.4)
mid = (lin.max(axis=2) < 0.6) & (lin.min(axis=2) > 0.02)
assert mid.mean() > 0.5, f"too few mid-tone pixels to compare ({mid.mean():.2f})"
mse_p3 = np.mean(((as_srgb - jp) ** 2)[mid])
print(f"P3 JPEG -> sRGB (LittleCMS) vs sRGB JPEG on {mid.mean():.0%} mid-tone pixels: PSNR {10 * np.log10(255 ** 2 / max(mse_p3, 1e-9)):.1f} dB")
assert mse_p3 < 40, "the P3 render, colour-managed to sRGB, should match the sRGB render"
print("selftest OK")
