#!/usr/bin/env python3
"""Independent Gate 2 verifier.

Recomputes every statistic and verdict from the RAW values in the app's gate2_*.json reports; it does not trust the
app's own verdict. Thresholds are fixed here from the gate definition (docs/PROOF_GATES.md), never read from a report.

  python3 tools/proof/check_gate2.py <report.json | directory> ...

HTP_INFERENCE_PROVEN=TRUE is printed only if some variant has at least three INDEPENDENT runs (distinct runId and
distinct process start), that all pass, on one device, against the committed model. Any failing report of that variant
(selective reporting) blocks the variant.
"""
import hashlib
import json
import math
import pathlib
import statistics
import sys

SCHEMA = "raphael.gate2.run/1"
PSNR_MIN_DB = 35.0
P95_MAX_MS = 100.0
ITERATIONS = 300
MAX_ABS_ERROR = 0.1
MAX_INPUT_VS_REF_PSNR_DB = 30.0
MAX_DRIFT_RATIO = 1.5
THERMAL_FAIL_STATUS = 3
MIN_INDEPENDENT_RUNS = 3
QNN = "QNNExecutionProvider"
REPO = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = REPO / "neural-runtime/src/main/resources/models/denoise_tiny_v1.manifest.json"


def p95_nearest_rank(values):
    s = sorted(values)
    return s[max(1, math.ceil(0.95 * len(s))) - 1]


def finite(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def committed_model_hashes():
    if not MANIFEST.exists():
        return {}
    return {k: v["sha256"] for k, v in json.loads(MANIFEST.read_text())["files"].items()}


def htp_failures(att):
    """Mirror of HtpAttributionRule in OrtBackend.kt, evaluated on the report's raw attribution details."""
    d = att.get("details") or {}
    prof = d.get("ortProfile") or {}
    counts = prof.get("providerCounts") or {}
    opts = d.get("providerOptions") or {}
    libs = d.get("mappedLibraries") or []
    out = []
    if d.get("sessionCreated") is not True:
        out.append("session was not created")
    if d.get("cpuEpFallbackDisabled") is not True:
        out.append("CPU EP fallback was not disabled")
    if opts.get("backend_type") != "htp":
        out.append("QNN backend_type is not htp")
    if prof.get("enabled") is not True:
        out.append("profiling disabled: no per-node provider evidence")
    elif not counts.get(QNN):
        out.append("no profiled node ran under " + QNN)
    others = sum(v for k, v in counts.items() if k != QNN)
    if others:
        out.append(f"{others} profiled node(s) ran under another provider: {counts}")
    if not any(isinstance(n, str) and n.startswith("libQnnHtp") for n in libs):
        out.append("no libQnnHtp* library is mapped into the process")
    return out


def check_report(r, model_hashes):
    """Returns (criteria, stats). criteria: list of (name, passed, detail)."""
    c = []

    def add(name, ok, detail):
        c.append((name, bool(ok), detail))

    add("schema", r.get("schema") == SCHEMA, str(r.get("schema")))
    add("run_completed_without_error", r.get("failure") is None, str(r.get("failure")))
    timing = r.get("timing") or {}
    its = timing.get("iterationsMs") or []
    add("iterations_300", len(its) == ITERATIONS and all(finite(x) and x > 0 for x in its), f"{len(its)} iterations")
    num = r.get("numerics") or {}
    psnr = num.get("psnrDb") or []
    mae = num.get("maxAbsError") or []
    add("psnr_ge_35", len(psnr) == ITERATIONS and all(finite(x) for x in psnr) and min(psnr) >= PSNR_MIN_DB,
        f"min {min(psnr) if psnr and all(finite(x) for x in psnr) else 'n/a'}")
    add("no_nan_inf", num.get("nonFiniteCount") == 0 and len(mae) == ITERATIONS and all(finite(x) for x in mae),
        f"nonFiniteCount={num.get('nonFiniteCount')}")
    add("no_gross_error", len(mae) == ITERATIONS and all(finite(x) for x in mae) and max(mae) <= MAX_ABS_ERROR,
        f"max {max(mae) if mae and all(finite(x) for x in mae) else 'n/a'}")
    inp = r.get("input") or {}
    add("model_discriminating", finite(inp.get("psnrInputVsReferenceDb")) and inp["psnrInputVsReferenceDb"] <= MAX_INPUT_VS_REF_PSNR_DB,
        str(inp.get("psnrInputVsReferenceDb")))
    stats = {}
    if len(its) == ITERATIONS and all(finite(x) for x in its):
        p95 = p95_nearest_rank(its)
        third = ITERATIONS // 3
        first, last = statistics.median(its[:third]), statistics.median(its[-third:])
        add("p95_le_100ms", p95 <= P95_MAX_MS, f"p95 {p95:.3f} ms")
        add("latency_stable", first > 0 and last <= MAX_DRIFT_RATIO * first, f"first {first:.3f} last {last:.3f}")
        loop_ms = timing.get("loopWallMs")
        stats = {
            "cold_ms": timing.get("coldMs"), "warm_median_ms": statistics.median(its), "p95_ms": p95, "worst_ms": max(its),
            "throughput_per_s": (ITERATIONS / (sum(its) / 1000.0)) if sum(its) > 0 else None,
            "session_create_ms": timing.get("sessionCreateMs"), "loop_wall_ms": loop_ms,
        }
    else:
        add("p95_le_100ms", False, "no valid iterations")
        add("latency_stable", False, "no valid iterations")
    samples = r.get("samples") or []
    thermal = [s["thermalStatus"] for s in samples if isinstance(s.get("thermalStatus"), int)]
    add("thermal_stable", len(thermal) >= 2 and max(thermal) < THERMAL_FAIL_STATUS, f"statuses {sorted(set(thermal))}")
    pss = [s["pssKb"] for s in samples if isinstance(s.get("pssKb"), int)]
    add("memory_sampled", len(pss) >= 2, f"{len(pss)} PSS samples")
    if pss:
        stats["pss_kb_first_last"] = (pss[0], pss[-1])
    cpu = [(s["elapsedRealtimeNanos"], s["processCpuTimeMs"]) for s in samples if isinstance(s.get("processCpuTimeMs"), int)]
    if len(cpu) >= 2 and cpu[-1][0] > cpu[0][0]:
        stats["process_cpu_utilization"] = (cpu[-1][1] - cpu[0][1]) / ((cpu[-1][0] - cpu[0][0]) / 1e6)
    att = r.get("attribution") or {}
    add("backend_is_qnn_npu", (r.get("backend") or {}).get("type") == "QUALCOMM_QNN_NPU", str((r.get("backend") or {}).get("type")))
    add("attribution_target_npu_htp", att.get("target") == "NPU_HTP", str(att.get("target")))
    fails = htp_failures(att)
    add("htp_attribution_rederived", not fails, "; ".join(fails) or "proven by raw evidence")
    steps = r.get("bringUp") or []
    add("session_create_ok", any(s.get("step") == "session.create" and s.get("ok") is True for s in steps), "bring-up log")
    mh = (att.get("details") or {}).get("modelSha256")
    resource = (r.get("model") or {}).get("resource")
    add("model_is_committed_artifact", bool(mh) and model_hashes.get(resource) == mh, f"{resource} sha256 {mh}")
    app = (r.get("verdict") or {})
    add("app_verdict_agrees", app.get("htpInferenceProvenThisRun") == all(ok for _, ok, _ in c), "app says " + str(app.get("htpInferenceProvenThisRun")))
    return c, stats


def aggregate_variant(runs):
    """runs: list of (report, passed). Returns (proven, human-readable info)."""
    independent = {(r.get("runId"), r.get("processStartElapsedRealtimeMs")) for r, _ in runs}
    starts = {r.get("processStartElapsedRealtimeMs") for r, _ in runs} - {None}
    devices = {json.dumps((r.get("device") or {}).get("fingerprint") or (r.get("device") or {}).get("model"), sort_keys=True) for r, _ in runs}
    hashes = {((r.get("input") or {}).get("sha256"), (r.get("input") or {}).get("referenceSha256")) for r, _ in runs}
    all_pass = all(ok for _, ok in runs)
    enough = len(independent) >= MIN_INDEPENDENT_RUNS and len(starts) >= MIN_INDEPENDENT_RUNS
    consistent = len(devices) == 1 and len(hashes) == 1
    info = (f"reports={len(runs)} independent={len(independent)} distinctProcessStarts={len(starts)} allPass={all_pass} "
            f"oneDevice={len(devices) == 1} sameInputAndReference={len(hashes) == 1}")
    return all_pass and enough and consistent, info


def load_reports(paths):
    files = []
    for p in map(pathlib.Path, paths):
        files += sorted(p.glob("gate2_*_*.json")) if p.is_dir() else [p]
    reports = []
    for f in files:
        if f.name == "gate2_suite_summary.json":
            continue
        try:
            reports.append((f, json.loads(f.read_text())))
        except (OSError, ValueError) as e:
            print(f"cannot read {f}: {e}", file=sys.stderr)
    return reports


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    model_hashes = committed_model_hashes()
    reports = load_reports(argv)
    by_variant = {}
    for f, r in reports:
        crit, stats = check_report(r, model_hashes)
        ok = all(p for _, p, _ in crit)
        by_variant.setdefault(r.get("variant"), []).append((f, r, crit, stats, ok))
        print(f"\n== {f.name}  variant={r.get('variant')}  {'PASS' if ok else 'FAIL'}")
        for name, passed, detail in crit:
            print(f"   [{'ok' if passed else '!!'}] {name}: {detail}")
        if stats:
            print("   stats:", {k: (round(v, 3) if isinstance(v, float) else v) for k, v in stats.items()})

    proven = []
    print("\n== aggregate")
    for variant, runs in sorted(by_variant.items(), key=lambda kv: str(kv[0])):
        verdict, info = aggregate_variant([(r, ok) for _, r, _, _, ok in runs])
        print(f"{variant}: {info} -> {'PROVEN' if verdict else 'not proven'}")
        if verdict:
            proven.append(variant)
    flag = bool(proven)
    print(f"\nHTP_INFERENCE_PROVEN={'TRUE' if flag else 'FALSE'}" + (f"  (variants: {', '.join(proven)})" if flag else ""))
    return 0 if flag else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
