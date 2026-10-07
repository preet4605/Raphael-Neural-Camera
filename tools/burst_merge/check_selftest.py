#!/usr/bin/env python3
"""Compares tool output to the synthetic ground truth: merged.pgm must beat the single reference frame."""
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
print("selftest OK")
