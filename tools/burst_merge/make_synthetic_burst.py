#!/usr/bin/env python3
"""Writes a SYNTHETIC RGGB DNG burst (hand-shake + Poisson-Gaussian noise) with known ground truth, to try the
burst_merge tool before real captures exist. Nothing here is camera data.

  python3 tools/burst_merge/make_synthetic_burst.py <out dir> [width height frames]

Writes frame_00.dng .. frame_NN.dng and truth.npy (the noise-free, shake-free mosaic of frame 0 on its normalized scale).
"""
import struct
import sys
import pathlib

import numpy as np

BLACK = 64.0
WHITE = 4095.0
SHOT, READ = 0.002, 0.0001
CHANNEL_SCALE = np.array([1.0, 0.8, 0.8, 0.6])  # R, Gr, Gb, B
CFA = bytes([0, 1, 1, 2])  # RGGB


def scene(x, y, w, h, comps):
    v = 0.35 + 0.10 * (x / w) + 0.08 * (y / h)
    for amp, fx, fy, ph in comps:
        v = v + amp * np.sin(2 * np.pi * (fx * x + fy * y) + ph)

    def smooth(d):
        t = np.clip((d + 0.75) / 1.5, 0.0, 1.0)
        return t * t * (3 - 2 * t)

    v = v + 0.25 * smooth(18.0 - np.hypot(x - 0.3 * w, y - 0.35 * h))
    v = v - 0.20 * smooth(np.minimum(np.minimum(x - 0.55 * w, 0.8 * w - x), np.minimum(y - 0.55 * h, 0.85 * h - y)))
    return np.clip(v, 0.03, 0.9)


def render(w, h, tx, ty, comps, rng):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    pos = (yy.astype(int) % 2) * 2 + (xx.astype(int) % 2)
    signal = scene(xx - tx, yy - ty, w, h, comps) * CHANNEL_SCALE[pos]
    noisy = signal + rng.normal(size=signal.shape) * np.sqrt(SHOT * np.maximum(signal, 0) + READ)
    dn = np.clip(np.round(BLACK + noisy * (WHITE - BLACK)), 0, WHITE).astype("<u2")
    return dn, signal


def write_dng(path, dn, exposure_s, iso):
    h, w = dn.shape
    o = "<"

    def sh(*v): return struct.pack(o + "H" * len(v), *v)
    def lo(*v): return struct.pack(o + "I" * len(v), *v)
    def rat(vals): return b"".join(struct.pack(o + "II", int(round(x * 1_000_000)), 1_000_000) for x in vals)

    raw = dn.tobytes()
    def entries(raw_off, sub_off, exif_off):
        ifd0 = [(254, 4, 1, lo(1)), (256, 4, 1, lo(1)), (257, 4, 1, lo(1)), (330, 4, 1, lo(sub_off)), (34665, 4, 1, lo(exif_off)),
                (50728, 5, 3, rat([0.55, 1.0, 0.62]))]
        raws = [(254, 4, 1, lo(0)), (256, 4, 1, lo(w)), (257, 4, 1, lo(h)), (258, 3, 1, sh(16)), (259, 3, 1, sh(1)),
                (262, 3, 1, sh(32803)), (273, 4, 1, lo(raw_off)), (277, 3, 1, sh(1)), (279, 4, 1, lo(len(raw))),
                (33421, 3, 2, sh(2, 2)), (33422, 1, 4, CFA), (50713, 3, 2, sh(2, 2)),
                (50714, 5, 4, rat([BLACK] * 4)), (50717, 4, 1, lo(int(WHITE))),
                (51041, 12, 6, struct.pack(o + "6d", SHOT, READ, SHOT, READ, SHOT, READ))]
        exif = [(33434, 5, 1, rat([exposure_s])), (34855, 3, 1, sh(iso))]
        return [ifd0, raws, exif]

    def layout(ifds):
        pos, offs = 8, []
        for e in ifds:
            offs.append(pos)
            pos += 2 + 12 * len(e) + 4
        vals = sum(len(b) + len(b) % 2 for e in ifds for (_, _, _, b) in e if len(b) > 4)
        return offs, pos, pos + vals

    offs, vstart, rawoff = layout(entries(0, 0, 0))
    ifds = entries(rawoff, offs[1], offs[2])
    out = b"II" + sh(42) + lo(offs[0])
    values, vpos = b"", vstart
    for e in ifds:
        e = sorted(e, key=lambda t: t[0])
        body = sh(len(e))
        for tag, typ, count, data in e:
            body += struct.pack(o + "HHI", tag, typ, count)
            if len(data) <= 4:
                body += data.ljust(4, b"\0")
            else:
                body += lo(vpos)
                pad = b"\0" if len(data) % 2 else b""
                values += data + pad
                vpos += len(data) + len(pad)
        out += body + lo(0)
    pathlib.Path(path).write_bytes(out + values + raw)


def main():
    out = pathlib.Path(sys.argv[1])
    w = int(sys.argv[2]) if len(sys.argv) > 2 else 512
    h = int(sys.argv[3]) if len(sys.argv) > 3 else 384
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 6
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(7)
    comps = [(rng.uniform(0.015, 0.05), rng.uniform(-0.15, 0.15), rng.uniform(-0.15, 0.15), rng.uniform(0, 2 * np.pi)) for _ in range(14)]
    for i in range(n):
        tx, ty = (0.0, 0.0) if i == 0 else (rng.uniform(-3, 3), rng.uniform(-3, 3))
        dn, signal = render(w, h, tx, ty, comps, rng)
        if i == 0:
            np.save(out / "truth.npy", signal.astype(np.float32))
        write_dng(out / f"frame_{i:02d}.dng", dn, 0.01, 100)
    print(f"wrote {n} frames {w}x{h} to {out}")


if __name__ == "__main__":
    main()
