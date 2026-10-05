"""Tests for check_gate1.py. All DNGs, reports and frames below are synthetic TEST FIXTURES at a tiny size."""
import csv
import hashlib
import json
import pathlib
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import check_gate1 as g  # noqa: E402

W, H = 16, 12


def build_dng(path, width, height, seed, bits=16, compression=1, photometric=g.CFA_PHOTOMETRIC, with_version=True,
              raw_len=None, byte_order="<"):
    """Minimal TIFF: IFD0 (thumbnail-like, DNGVersion, SubIFDs) + raw SubIFD with one strip."""
    bo = byte_order
    raw = hashlib.sha256(str(seed).encode()).digest() * ((width * height * 2) // 32 + 1)
    raw = raw[: (raw_len if raw_len is not None else width * height * 2)]

    def entry(tag, typ, count, value):
        size = g.TIFF_TYPE_SIZE[typ] * count
        if isinstance(value, bytes):
            packed = value
        else:
            fmt = {1: "B", 3: "H", 4: "I"}[typ]
            packed = struct.pack(bo + fmt * count, *value)
        inline = packed.ljust(4, b"\0") if size <= 4 else None
        return tag_bytes(tag, typ, count, inline, packed)

    def tag_bytes(tag, typ, count, inline, packed):
        return (tag, typ, count, inline, packed)

    ifd0_off = 8
    ifd0_entries = 3 if with_version else 2
    ifd0_size = 2 + 12 * ifd0_entries + 4
    raw_ifd_off = ifd0_off + ifd0_size
    raw_entries = 9
    raw_ifd_size = 2 + 12 * raw_entries + 4
    data_off = raw_ifd_off + raw_ifd_size

    def ifd(entries):
        out = struct.pack(bo + "H", len(entries))
        for tag, typ, count, inline, packed in sorted(entries, key=lambda e: e[0]):
            out += struct.pack(bo + "HHI", tag, typ, count) + (inline if inline is not None else b"\0\0\0\0")
        return out + struct.pack(bo + "I", 0)

    ifd0 = [entry(g.TAG["NewSubfileType"], 4, 1, [1]), entry(g.TAG["SubIFDs"], 4, 1, [raw_ifd_off])]
    if with_version:
        ifd0.append(entry(g.TAG["DNGVersion"], 1, 4, bytes([1, 4, 0, 0])))
    raw_ifd = [
        entry(g.TAG["NewSubfileType"], 4, 1, [0]), entry(g.TAG["ImageWidth"], 4, 1, [width]), entry(g.TAG["ImageLength"], 4, 1, [height]),
        entry(g.TAG["BitsPerSample"], 3, 1, [bits]), entry(g.TAG["Compression"], 3, 1, [compression]),
        entry(g.TAG["Photometric"], 3, 1, [photometric]), entry(g.TAG["StripOffsets"], 4, 1, [data_off]),
        entry(g.TAG["SamplesPerPixel"], 3, 1, [1]), entry(g.TAG["StripByteCounts"], 4, 1, [len(raw)]),
    ]
    blob = (b"II*\0" if bo == "<" else b"MM\0*") + struct.pack(bo + "I", ifd0_off) + ifd(ifd0) + ifd(raw_ifd) + raw
    pathlib.Path(path).write_bytes(blob)
    return hashlib.sha256(raw).hexdigest()


def make_run(root, run_id="r1", start=1000, n=8, mutate=None):
    d = pathlib.Path(root) / f"run_{run_id}"
    d.mkdir(parents=True)
    frames = []
    for i in range(n):
        name = f"frame_{i:02d}.dng"
        build_dng(d / name, W, H, seed=f"{run_id}-{i}")
        ts = 1_000_000_000 + i * 150_000_000
        frames.append({
            "requestIndex": i, "frameNumber": 100 + i, "sensorTimestampNs": ts, "imageTimestampNs": ts,
            "imageArrivalElapsedNs": ts + 500_000_000, "resultArrivalElapsedNs": ts + 400_000_000, "width": W, "height": H,
            "format": g.RAW_SENSOR, "exposureTimeNs": 10_000_000, "iso": 100, "rowStride": W * 2, "pixelStride": 2,
            "planeBytes": W * H * 2, "pixelSha256": f"{run_id}-hash-{i}", "dngFile": name,
            "dngBytes": (d / name).stat().st_size, "dropped": False, "droppedReason": None,
        })
    report = {
        "schema": g.SCHEMA, "runId": run_id, "processStartElapsedRealtimeMs": start, "device": {"model": "CPH2745", "fingerprint": "fp"},
        "app": {"isSystemApp": False, "uid": 10234}, "camera": {}, "target": {"width": W, "height": H, "requestedFrames": n},
        "timestampSourceRealtime": True, "frames": frames, "events": ["INFO ok"], "completedNormally": True, "failure": None,
    }
    if mutate:
        mutate(report, d)
    (d / "gate1_report.json").write_text(json.dumps(report))
    with open(d / "frames.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["request_index", "sensor_timestamp_ns"])
        for f in report["frames"]:
            w.writerow([f["requestIndex"], f["sensorTimestampNs"]])
    return d


def failed(d):
    _, crit = g.check_run(d, width=W, height=H)
    return {n for n, ok, _ in crit if not ok}


class CheckGate1Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_run_passes(self):
        self.assertEqual(failed(make_run(self.root)), set())

    def test_big_endian_dng_is_parsed_too(self):
        d = make_run(self.root)
        build_dng(d / "frame_00.dng", W, H, seed="be", byte_order=">")
        rep = json.loads((d / "gate1_report.json").read_text())
        rep["frames"][0]["dngBytes"] = (d / "frame_00.dng").stat().st_size
        (d / "gate1_report.json").write_text(json.dumps(rep))
        self.assertEqual(failed(d), set())

    def test_report_defects(self):
        cases = {
            "every_frame_full_resolution": lambda r, d: r["frames"][2].update(width=8, height=6),
            "every_frame_raw_sensor_format": lambda r, d: r["frames"][2].update(format=0x100),
            "zero_drops": lambda r, d: r["frames"][2].update(dropped=True),
            "frame_numbers_consecutive": lambda r, d: r["frames"][4].update(frameNumber=999),
            "sensor_timestamps_strictly_monotonic": lambda r, d: r["frames"][5].update(sensorTimestampNs=1, imageTimestampNs=1),
            "image_timestamp_equals_result_timestamp": lambda r, d: r["frames"][1].update(imageTimestampNs=5),
            "metadata_valid": lambda r, d: r["frames"][3].update(exposureTimeNs=0),
            "no_duplicate_frames_by_reported_hash": lambda r, d: r["frames"][7].update(pixelSha256=r["frames"][0]["pixelSha256"]),
            "arrival_not_before_capture": lambda r, d: r["frames"][2].update(imageArrivalElapsedNs=1),
            "ordinary_non_system_app": lambda r, d: r["app"].update(isSystemApp=True),
            "run_completed_normally": lambda r, d: r.update(completedNormally=False, failure="crash"),
            "at_least_8_frames_requested": lambda r, d: (r.update(frames=r["frames"][:7]), r["target"].update(requestedFrames=7)),
            "all_requested_frames_present": lambda r, d: r.update(frames=[f for f in r["frames"] if f["requestIndex"] != 3]),
        }
        for crit, mutate in cases.items():
            d = make_run(tempfile.mkdtemp(dir=self.root), mutate=mutate)
            self.assertIn(crit, failed(d), crit)

    def test_lost_buffer_event_fails_zero_drops(self):
        d = make_run(self.root, mutate=lambda r, _: r["events"].append("LOST index=3 frame=103 buffer lost"))
        self.assertIn("zero_drops", failed(d))

    def test_dng_defects_are_found_from_the_files_themselves(self):
        d = make_run(self.root)
        # duplicate pixels hidden behind distinct reported hashes
        (d / "frame_03.dng").write_bytes((d / "frame_02.dng").read_bytes())
        rep = json.loads((d / "gate1_report.json").read_text())
        rep["frames"][3]["dngBytes"] = (d / "frame_03.dng").stat().st_size
        (d / "gate1_report.json").write_text(json.dumps(rep))
        self.assertIn("no_duplicate_frames_by_dng_pixels", failed(d))

    def test_dng_structure_problems(self):
        def set_dng(**kw):
            def mutate(r, d):
                build_dng(d / "frame_01.dng", W, H, seed="x", **kw)
                r["frames"][1]["dngBytes"] = (d / "frame_01.dng").stat().st_size
            return mutate
        for kw in ({"bits": 12}, {"compression": 7}, {"photometric": 2}, {"with_version": False}, {"raw_len": 100}):
            d = make_run(tempfile.mkdtemp(dir=self.root), mutate=set_dng(**kw))
            self.assertIn("dng_files_valid_full_resolution_raw", failed(d), kw)

    def test_missing_wrong_size_or_garbage_dng(self):
        d = make_run(self.root, run_id="a")
        (d / "frame_00.dng").unlink()
        self.assertIn("dng_files_valid_full_resolution_raw", failed(d))
        d = make_run(self.root, run_id="b")
        (d / "frame_00.dng").write_bytes(b"not a dng at all")
        self.assertIn("dng_files_valid_full_resolution_raw", failed(d))
        d = make_run(self.root, run_id="c", mutate=lambda r, _: r["frames"][0].update(dngBytes=123))
        self.assertIn("dng_files_valid_full_resolution_raw", failed(d))

    def test_csv_mismatch_is_flagged(self):
        d = make_run(self.root)
        (d / "frames.csv").write_text("request_index,sensor_timestamp_ns\n0,1\n")
        self.assertIn("frames_csv_matches_report", failed(d))

    def test_three_independent_passing_runs_prove_the_gate(self):
        runs = []
        for i in range(3):
            d = make_run(tempfile.mkdtemp(dir=self.root), run_id=f"r{i}", start=1000 + i)
            r, crit = g.check_run(d, width=W, height=H)
            runs.append((r, all(ok for _, ok, _ in crit)))
        self.assertTrue(g.aggregate(runs)[0])

    def test_fewer_dependent_or_failing_runs_do_not_prove_it(self):
        def run(i, start, **kw):
            d = make_run(tempfile.mkdtemp(dir=self.root), run_id=f"r{i}", start=start, **kw)
            r, crit = g.check_run(d, width=W, height=H)
            return r, all(ok for _, ok, _ in crit)
        self.assertFalse(g.aggregate([run(0, 1), run(1, 2)])[0])
        self.assertFalse(g.aggregate([run(0, 1), run(1, 1), run(2, 1)])[0])
        bad = run(3, 4, mutate=lambda r, _: r["frames"][0].update(dropped=True))
        self.assertFalse(g.aggregate([run(0, 1), run(1, 2), run(2, 3), bad])[0])


if __name__ == "__main__":
    unittest.main()
