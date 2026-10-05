"""Tests for check_gate2.py. The report builder below is a TEST FIXTURE; none of it is device evidence."""
import copy
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import check_gate2 as g  # noqa: E402

HASHES = g.committed_model_hashes()
RESOURCE = "denoise_tiny_v1_fp32.onnx"


def good_report(run_id="r1", start=1000, variant="htp-fp32"):
    its = [3.0 + (i % 7) * 0.01 for i in range(300)]
    return {
        "schema": g.SCHEMA, "runId": run_id, "processStartElapsedRealtimeMs": start, "variant": variant,
        "device": {"model": "CPH2745", "fingerprint": "fp"}, "failure": None,
        "backend": {"type": "QUALCOMM_QNN_NPU"}, "model": {"id": "denoise-tiny-v1", "resource": RESOURCE},
        "input": {"sha256": "aa", "referenceSha256": "bb", "psnrInputVsReferenceDb": 24.5},
        "timing": {"sessionCreateMs": 900.0, "coldMs": 40.0, "loopWallMs": 1500.0, "iterationsMs": its, "warmupMs": [3.0] * 10},
        "numerics": {"psnrDb": [60.0] * 300, "maxAbsError": [0.002] * 300, "nonFiniteCount": 0},
        "samples": [{"iteration": i, "elapsedRealtimeNanos": i * 10**9, "thermalStatus": 0, "pssKb": 200000 + i,
                     "processCpuTimeMs": i * 100} for i in range(0, 301, 10)],
        "bringUp": [{"step": "session.create", "ok": True, "detail": "x"}],
        "attribution": {
            "target": "NPU_HTP", "wholeGraphOnTarget": True, "provesHtp": True, "evidence": ["x"],
            "details": {
                "sessionCreated": True, "cpuEpFallbackDisabled": True, "providerOptions": {"backend_type": "htp"},
                "ortProfile": {"enabled": True, "providerCounts": {g.QNN: 63}},
                "mappedLibraries": ["libQnnHtp.so", "libQnnHtpV81Stub.so", "libcdsprpc.so"],
                "modelSha256": HASHES.get(RESOURCE, "missing-manifest"),
            },
        },
        "verdict": {"htpInferenceProvenThisRun": True},
    }


def failed(report):
    crit, _ = g.check_report(report, HASHES)
    return {n for n, ok, _ in crit if not ok}


class CheckGate2Test(unittest.TestCase):
    def test_manifest_is_present(self):
        self.assertIn(RESOURCE, HASHES)

    def test_good_fixture_passes_every_criterion(self):
        self.assertEqual(failed(good_report()), set())

    def test_percentile_is_nearest_rank(self):
        self.assertEqual(g.p95_nearest_rank(list(range(1, 301))), 285)

    def test_each_defect_is_caught(self):
        cases = {
            "p95_le_100ms": lambda r: r["timing"].__setitem__("iterationsMs", [3.0] * 284 + [150.0] * 16),
            "psnr_ge_35": lambda r: r["numerics"]["psnrDb"].__setitem__(7, 34.9),
            "no_gross_error": lambda r: r["numerics"]["maxAbsError"].__setitem__(1, 0.5),
            "no_nan_inf": lambda r: r["numerics"].__setitem__("nonFiniteCount", 1),
            "iterations_300": lambda r: r["timing"].__setitem__("iterationsMs", r["timing"]["iterationsMs"][:299]),
            "thermal_stable": lambda r: r["samples"][5].__setitem__("thermalStatus", 3),
            "memory_sampled": lambda r: [s.pop("pssKb") for s in r["samples"]],
            "model_discriminating": lambda r: r["input"].__setitem__("psnrInputVsReferenceDb", 45.0),
            "run_completed_without_error": lambda r: r.__setitem__("failure", "boom"),
            "attribution_target_npu_htp": lambda r: r["attribution"].__setitem__("target", "CPU_RUNTIME"),
            "backend_is_qnn_npu": lambda r: r["backend"].__setitem__("type", "ORT_CPU"),
            "session_create_ok": lambda r: r.__setitem__("bringUp", []),
            "model_is_committed_artifact": lambda r: r["attribution"]["details"].__setitem__("modelSha256", "deadbeef"),
        }
        for crit, mutate in cases.items():
            r = good_report()
            mutate(r)
            self.assertIn(crit, failed(r), crit)

    def test_null_psnr_or_error_values_fail(self):
        r = good_report()
        r["numerics"]["psnrDb"][3] = None
        self.assertIn("psnr_ge_35", failed(r))
        r = good_report()
        r["numerics"]["maxAbsError"][3] = None
        self.assertIn("no_gross_error", failed(r))

    def test_latency_drift_is_caught(self):
        r = good_report()
        r["timing"]["iterationsMs"] = [3.0] * 100 + [3.0] * 100 + [9.0] * 100
        self.assertIn("latency_stable", failed(r))

    def test_htp_rederivation_ignores_the_apps_own_claim(self):
        mutations = {
            "no fallback disabled": lambda d: d.__setitem__("cpuEpFallbackDisabled", False),
            "cpu nodes present": lambda d: d["ortProfile"]["providerCounts"].__setitem__("CPUExecutionProvider", 2),
            "no profile": lambda d: d["ortProfile"].__setitem__("enabled", False),
            "no qnn nodes": lambda d: d["ortProfile"].__setitem__("providerCounts", {}),
            "wrong backend": lambda d: d["providerOptions"].__setitem__("backend_type", "cpu"),
            "no htp lib": lambda d: d.__setitem__("mappedLibraries", ["libQnnGpu.so"]),
        }
        for name, mutate in mutations.items():
            r = good_report()
            mutate(r["attribution"]["details"])  # app still says provesHtp=True and target=NPU_HTP
            self.assertIn("htp_attribution_rederived", failed(r), name)

    def test_app_verdict_disagreement_is_flagged(self):
        r = good_report()
        r["verdict"]["htpInferenceProvenThisRun"] = False
        self.assertIn("app_verdict_agrees", failed(r))

    def aggregate(self, reports):
        return g.aggregate_variant([(r, not failed(r)) for r in reports])

    def test_three_independent_passing_runs_prove_the_variant(self):
        proven, _ = self.aggregate([good_report(f"r{i}", 1000 + i) for i in range(3)])
        self.assertTrue(proven)

    def test_two_runs_are_not_enough(self):
        self.assertFalse(self.aggregate([good_report(f"r{i}", 1000 + i) for i in range(2)])[0])

    def test_same_process_start_is_not_independent(self):
        self.assertFalse(self.aggregate([good_report(f"r{i}", 1000) for i in range(3)])[0])

    def test_missing_process_start_is_not_independent(self):
        self.assertFalse(self.aggregate([good_report(f"r{i}", None) for i in range(3)])[0])

    def test_a_failing_run_blocks_the_variant(self):
        runs = [good_report(f"r{i}", 1000 + i) for i in range(4)]
        runs[3]["numerics"]["psnrDb"][0] = 10.0
        self.assertFalse(self.aggregate(runs)[0])

    def test_mixed_devices_or_inputs_block_the_variant(self):
        runs = [good_report(f"r{i}", 1000 + i) for i in range(3)]
        runs[2]["device"] = {"model": "other", "fingerprint": "zz"}
        self.assertFalse(self.aggregate(runs)[0])
        runs = [good_report(f"r{i}", 1000 + i) for i in range(3)]
        runs[1]["input"] = {**runs[1]["input"], "sha256": "different"}
        self.assertFalse(self.aggregate(runs)[0])


if __name__ == "__main__":
    unittest.main()
