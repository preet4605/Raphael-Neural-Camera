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
print("selftest OK")
