#!/usr/bin/env python3
"""Independent Gate 1 verifier (full-resolution third-party RAW burst).

Re-derives every verdict from the RAW per-frame records and from the persisted DNG files themselves (it parses the
DNGs and hashes their raw pixel data, so duplicate detection does not rely on the app's own hashes). The app's own
evaluation is ignored. Thresholds come from the gate definition (docs/PROOF_GATES.md).

  python3 tools/proof/check_gate1.py <run dir | directory containing run dirs> ...

RAW_BURST_PROVEN=TRUE is printed only with at least three independent runs (distinct run ids and process starts) on one
device, all passing, with no failing run among those provided.
"""
import csv
import hashlib
import json
import pathlib
import struct
import sys

SCHEMA = "raphael.gate1.run/1"
FULL_W, FULL_H = 8192, 6144
RAW_SENSOR = 0x20
MIN_FRAMES = 8
MIN_RUNS = 3

TIFF_TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8, 17: 8, 18: 8}
TAG = dict(NewSubfileType=254, ImageWidth=256, ImageLength=257, BitsPerSample=258, Compression=259, Photometric=262,
           StripOffsets=273, SamplesPerPixel=277, RowsPerStrip=278, StripByteCounts=279, SubIFDs=330,
           TileOffsets=324, TileByteCounts=325, DNGVersion=50706)
CFA_PHOTOMETRIC = 32803


class TiffError(Exception):
    pass


def read_ifd(f, offset, bo):
    f.seek(offset)
    raw = f.read(2)
    if len(raw) != 2:
        raise TiffError("truncated IFD")
    (n,) = struct.unpack(bo + "H", raw)
    entries = {}
    for _ in range(n):
        e = f.read(12)
        if len(e) != 12:
            raise TiffError("truncated IFD entry")
        tag, typ, count = struct.unpack(bo + "HHI", e[:8])
        size = TIFF_TYPE_SIZE.get(typ)
        if size is None:
            continue
        total = size * count
        if total <= 4:
            data = e[8:8 + total]
        else:
            (off,) = struct.unpack(bo + "I", e[8:12])
            pos = f.tell()
            f.seek(off)
            data = f.read(total)
            f.seek(pos)
            if len(data) != total:
                raise TiffError(f"tag {tag} data out of file bounds")
        fmt = {1: "B", 2: "B", 3: "H", 4: "I", 6: "b", 7: "B", 8: "h", 9: "i", 13: "I"}.get(typ)
        entries[tag] = list(struct.unpack(bo + fmt * count, data)) if fmt else data
    nxt = f.read(4)
    return entries, (struct.unpack(bo + "I", nxt)[0] if len(nxt) == 4 else 0)


def find_raw_ifd(path, width, height):
    """Returns (info dict) for the DNG's full-resolution raw IFD, or raises TiffError."""
    with open(path, "rb") as f:
        head = f.read(8)
        if head[:2] == b"II":
            bo = "<"
        elif head[:2] == b"MM":
            bo = ">"
        else:
            raise TiffError("not a TIFF/DNG (bad byte-order mark)")
        magic, ifd0 = struct.unpack(bo + "HI", head[2:8])
        if magic != 42:
            raise TiffError("bad TIFF magic")
        queue, seen, found, dng_version = [ifd0], set(), None, None
        while queue:
            off = queue.pop(0)
            if off == 0 or off in seen:
                continue
            seen.add(off)
            entries, nxt = read_ifd(f, off, bo)
            if off == ifd0:
                dng_version = entries.get(TAG["DNGVersion"])
            queue.append(nxt)
            queue.extend(entries.get(TAG["SubIFDs"], []))
            w = (entries.get(TAG["ImageWidth"]) or [None])[0]
            h = (entries.get(TAG["ImageLength"]) or [None])[0]
            if w == width and h == height:
                found = entries
        if found is None:
            raise TiffError(f"no IFD with ImageWidth/ImageLength == {width}x{height}")
        offsets = found.get(TAG["StripOffsets"]) or found.get(TAG["TileOffsets"])
        counts = found.get(TAG["StripByteCounts"]) or found.get(TAG["TileByteCounts"])
        if not offsets or not counts or len(offsets) != len(counts):
            raise TiffError("raw IFD has no strip/tile offsets and byte counts")
        h = hashlib.sha256()
        total = 0
        for o, c in zip(offsets, counts):
            f.seek(o)
            left = c
            while left:
                chunk = f.read(min(left, 1 << 20))
                if not chunk:
                    raise TiffError("raw data truncated")
                h.update(chunk)
                left -= len(chunk)
            total += c
        return {
            "bits": (found.get(TAG["BitsPerSample"]) or [None])[0],
            "compression": (found.get(TAG["Compression"]) or [None])[0],
            "photometric": (found.get(TAG["Photometric"]) or [None])[0],
            "samples": (found.get(TAG["SamplesPerPixel"]) or [1])[0],
            "raw_bytes": total,
            "raw_sha256": h.hexdigest(),
            "dng_version": dng_version,
        }


def check_run(run_dir, width=FULL_W, height=FULL_H, min_frames=MIN_FRAMES):
    """Returns (report, criteria). criteria: list of (name, passed, detail)."""
    run_dir = pathlib.Path(run_dir)
    r = json.loads((run_dir / "gate1_report.json").read_text())
    c = []

    def add(name, ok, detail):
        c.append((name, bool(ok), str(detail)))

    frames = sorted(r.get("frames") or [], key=lambda x: x.get("requestIndex", -1))
    target = r.get("target") or {}
    add("schema", r.get("schema") == SCHEMA, r.get("schema"))
    add("run_completed_normally", r.get("completedNormally") is True and r.get("failure") is None, r.get("failure"))
    add("at_least_8_frames_requested", (target.get("requestedFrames") or 0) >= min_frames, target.get("requestedFrames"))
    add("all_requested_frames_present",
        len(frames) == target.get("requestedFrames") and [f.get("requestIndex") for f in frames] == list(range(len(frames))),
        f"{len(frames)} records")
    add("target_is_full_resolution", target.get("width") == width and target.get("height") == height, f"{target.get('width')}x{target.get('height')}")
    add("every_frame_full_resolution", bool(frames) and all(f.get("width") == width and f.get("height") == height for f in frames), "")
    add("every_frame_raw_sensor_format", bool(frames) and all(f.get("format") == RAW_SENSOR for f in frames), [f.get("format") for f in frames])
    events = r.get("events") or []
    add("zero_drops", not any(f.get("dropped") for f in frames) and not any(str(e).startswith(("LOST", "FAILED")) for e in events),
        f"dropped={sum(1 for f in frames if f.get('dropped'))}")
    nums = [f.get("frameNumber") for f in frames]
    add("frame_numbers_consecutive", all(isinstance(n, int) for n in nums) and all(b == a + 1 for a, b in zip(nums, nums[1:])), nums)
    ts = [f.get("sensorTimestampNs") for f in frames]
    add("sensor_timestamps_strictly_monotonic", all(isinstance(t, int) for t in ts) and all(b > a for a, b in zip(ts, ts[1:])), ts)
    add("image_timestamp_equals_result_timestamp",
        bool(frames) and all(f.get("imageTimestampNs") is not None and f.get("imageTimestampNs") == f.get("sensorTimestampNs") for f in frames), "")
    add("metadata_valid", bool(frames) and all((f.get("exposureTimeNs") or 0) > 0 and (f.get("iso") or 0) > 0
                                                and (f.get("rowStride") or 0) > 0 and (f.get("pixelStride") or 0) > 0 for f in frames), "")
    add("plane_covers_full_frame", bool(frames) and all((f.get("planeBytes") or 0) >= (f.get("rowStride") or 1 << 60) * (f.get("height") or 1 << 60) for f in frames), "")
    if r.get("timestampSourceRealtime") is True:
        add("arrival_not_before_capture", all((f.get("imageArrivalElapsedNs") or -1) >= (f.get("sensorTimestampNs") or 1 << 62) for f in frames), "REALTIME source")
    else:
        add("arrival_not_before_capture", True, "timestamp source not REALTIME: not checked")
    reported = [f.get("pixelSha256") for f in frames]
    add("no_duplicate_frames_by_reported_hash", bool(reported) and None not in reported and len(set(reported)) == len(reported),
        f"{len(set(reported))} distinct of {len(reported)}")

    dng_hashes = []
    dng_problems = []
    for f in frames:
        name = f.get("dngFile")
        p = run_dir / name if name else None
        if not p or not p.is_file():
            dng_problems.append(f"{name}: missing")
            continue
        if f.get("dngBytes") != p.stat().st_size:
            dng_problems.append(f"{name}: size {p.stat().st_size} != reported {f.get('dngBytes')}")
        try:
            info = find_raw_ifd(p, width, height)
        except (TiffError, OSError, struct.error) as e:
            dng_problems.append(f"{name}: {e}")
            continue
        if info["bits"] != 16 or info["compression"] != 1 or info["photometric"] != CFA_PHOTOMETRIC or info["samples"] != 1:
            dng_problems.append(f"{name}: raw IFD is not uncompressed 16-bit CFA ({info})")
        if info["raw_bytes"] != width * height * 2:
            dng_problems.append(f"{name}: raw data {info['raw_bytes']} bytes, expected {width * height * 2}")
        if not info["dng_version"]:
            dng_problems.append(f"{name}: no DNGVersion tag")
        dng_hashes.append(info["raw_sha256"])
    add("dng_files_valid_full_resolution_raw", bool(frames) and not dng_problems, "; ".join(dng_problems[:3]) or f"{len(dng_hashes)} DNGs parsed")
    add("no_duplicate_frames_by_dng_pixels", len(dng_hashes) == len(frames) and len(set(dng_hashes)) == len(dng_hashes) and bool(dng_hashes),
        f"{len(set(dng_hashes))} distinct of {len(dng_hashes)} parsed")

    try:
        with open(run_dir / "frames.csv", newline="") as fh:
            rows = list(csv.DictReader(fh))
        csv_ok = len(rows) == len(frames) and all(
            str(row["sensor_timestamp_ns"]) == str(f.get("sensorTimestampNs")) for row, f in zip(rows, frames))
    except (OSError, KeyError):
        csv_ok = False
    add("frames_csv_matches_report", csv_ok, "frames.csv")

    app = r.get("app") or {}
    add("ordinary_non_system_app", app.get("isSystemApp") is False and isinstance(app.get("uid"), int) and app["uid"] >= 10000,
        f"isSystemApp={app.get('isSystemApp')} uid={app.get('uid')}")
    return r, c


def aggregate(runs):
    """runs: list of (report, passed). Returns (proven, info)."""
    ids = {x.get("runId") for x, _ in runs}
    starts = {x.get("processStartElapsedRealtimeMs") for x, _ in runs} - {None}
    devices = {json.dumps((x.get("device") or {}).get("fingerprint") or (x.get("device") or {}).get("model"), sort_keys=True) for x, _ in runs}
    all_pass = all(ok for _, ok in runs)
    enough = len(ids) >= MIN_RUNS and len(starts) >= MIN_RUNS
    info = f"runs={len(runs)} distinctRunIds={len(ids)} distinctProcessStarts={len(starts)} allPass={all_pass} oneDevice={len(devices) == 1}"
    return all_pass and enough and len(devices) == 1, info


def find_run_dirs(paths):
    dirs = []
    for p in map(pathlib.Path, paths):
        if (p / "gate1_report.json").is_file():
            dirs.append(p)
        elif p.is_dir():
            dirs += sorted(x.parent for x in p.rglob("gate1_report.json"))
    return dirs


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    runs = []
    for d in find_run_dirs(argv):
        try:
            r, crit = check_run(d)
        except (OSError, ValueError) as e:
            print(f"cannot read {d}: {e}", file=sys.stderr)
            continue
        ok = all(p for _, p, _ in crit)
        runs.append((r, ok))
        print(f"\n== {d}  {'PASS' if ok else 'FAIL'}")
        for name, passed, detail in crit:
            print(f"   [{'ok' if passed else '!!'}] {name}: {detail[:140]}")
    proven, info = aggregate(runs) if runs else (False, "no runs found")
    print(f"\n== aggregate\n{info}")
    print(f"\nRAW_BURST_PROVEN={'TRUE' if proven else 'FALSE'}")
    return 0 if proven else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
