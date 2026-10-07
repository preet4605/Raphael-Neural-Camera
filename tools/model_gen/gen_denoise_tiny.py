#!/usr/bin/env python3
"""Generates the deterministic denoise-shaped test network used by the runtime proof gates.

The network is UNTRAINED: fixed pseudo-random weights chosen so the residual branch is large enough that
a pass-through (output == input) cannot reach the Gate 2 PSNR threshold. It validates numerics, latency
and backend attribution; it does not denoise meaningfully and must never enter the camera path.

Outputs (default: neural-runtime/src/main/resources/models/):
  denoise_tiny_v1.weights.f32   little-endian float32 blob: w1,b1,w2,b2,w3,b3 (OIHW row-major)
  denoise_tiny_v1_fp32.onnx     FP32 graph (also the HTP FP16-precision path)
  denoise_tiny_v1_qdq_a16w8.onnx  QNN-style QDQ graph (uint16 activations, uint8 weights)
  denoise_tiny_v1_qdq_a8w8.onnx   QNN-style QDQ graph (uint8 activations, uint8 weights)
  denoise_tiny_v1.manifest.json sizes + sha256 of the committed files

The committed files, not regeneration, are the source of truth (onnx serialization can differ by version).
"""
import hashlib
import json
import pathlib
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config

SEED = 20260925
H = W = 256
LAYERS = [(1, 8, True), (8, 8, True), (8, 1, False)]  # (in, out, relu)
TARGET_RESIDUAL_RMS = 0.06  # => PSNR(input, output) ~= 24 dB, far below the 35 dB gate


def scene(seed: int) -> np.ndarray:
    """Smooth synthetic scene + Gaussian noise, float32 [1,1,H,W] in [0,1]. Calibration data only."""
    rs = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64) / H
    base = 0.5 + 0.22 * np.sin(2 * np.pi * (1.5 * xx + 0.5 * yy)) + 0.12 * np.cos(2 * np.pi * 3 * yy)
    cx, cy, r = 0.3 + 0.4 * rs.rand(), 0.3 + 0.4 * rs.rand(), 0.12 + 0.1 * rs.rand()
    base += 0.2 * (((xx - cx) ** 2 + (yy - cy) ** 2) < r * r)
    noisy = np.clip(base + rs.normal(0.0, 0.05, base.shape), 0.0, 1.0)
    return noisy.astype(np.float32)[None, None]


def conv3x3(x, w, b):
    n, c, h, wd = x.shape
    oc = w.shape[0]
    p = np.pad(x.astype(np.float64), ((0, 0), (0, 0), (1, 1), (1, 1)))
    out = np.zeros((n, oc, h, wd), dtype=np.float64)
    for o in range(oc):
        for i in range(c):
            for ky in range(3):
                for kx in range(3):
                    out[:, o] += float(w[o, i, ky, kx]) * p[:, i, ky:ky + h, kx:kx + wd]
        out[:, o] += float(b[o])
    return out.astype(np.float32)


def forward(x, params):
    (w1, b1, w2, b2, w3, b3) = params
    a = np.maximum(conv3x3(x, w1, b1), 0)
    a = np.maximum(conv3x3(a, w2, b2), 0)
    return x + conv3x3(a, w3, b3)


def make_params():
    rs = np.random.RandomState(SEED)
    w1 = rs.normal(0.0, 0.40, (8, 1, 3, 3)).astype(np.float32)
    b1 = (0.02 + 0.02 * rs.rand(8)).astype(np.float32)
    w2 = rs.normal(0.0, 0.14, (8, 8, 3, 3)).astype(np.float32)
    b2 = (0.02 + 0.02 * rs.rand(8)).astype(np.float32)
    w3 = rs.normal(0.0, 0.05, (1, 8, 3, 3)).astype(np.float32)
    b3 = np.zeros(1, dtype=np.float32)
    # Calibrate the residual branch so rms(output - input) ~= TARGET_RESIDUAL_RMS on the calibration scene.
    x = scene(1)
    r = forward(x, (w1, b1, w2, b2, w3, b3)) - x
    scale = TARGET_RESIDUAL_RMS / float(np.sqrt(np.mean(r.astype(np.float64) ** 2)))
    w3 = (w3 * scale).astype(np.float32)
    return (w1, b1, w2, b2, w3, b3)


def build_onnx(params) -> onnx.ModelProto:
    names = ["w1", "b1", "w2", "b2", "w3", "b3"]
    inits = [numpy_helper.from_array(p, n) for p, n in zip(params, names)]
    conv = dict(kernel_shape=[3, 3], pads=[1, 1, 1, 1], strides=[1, 1], dilations=[1, 1])
    nodes = [
        helper.make_node("Conv", ["input", "w1", "b1"], ["c1"], name="conv1", **conv),
        helper.make_node("Relu", ["c1"], ["a1"], name="relu1"),
        helper.make_node("Conv", ["a1", "w2", "b2"], ["c2"], name="conv2", **conv),
        helper.make_node("Relu", ["c2"], ["a2"], name="relu2"),
        helper.make_node("Conv", ["a2", "w3", "b3"], ["r"], name="conv3", **conv),
        helper.make_node("Add", ["input", "r"], ["output"], name="residual_add"),
    ]
    graph = helper.make_graph(
        nodes, "denoise_tiny_v1",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1, H, W])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 1, H, W])],
        initializer=inits,
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="raphael-model-gen")
    model.ir_version = 8
    onnx.checker.check_model(model)
    return model


class Calib(CalibrationDataReader):
    def __init__(self, n=24):
        self._it = iter([{"input": scene(1000 + i)} for i in range(n)])

    def get_next(self):
        return next(self._it, None)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def psnr(a, b):
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    return float("inf") if mse == 0 else 10 * np.log10(1.0 / mse)


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "neural-runtime/src/main/resources/models"
    out.mkdir(parents=True, exist_ok=True)
    params = make_params()

    blob = out / "denoise_tiny_v1.weights.f32"
    blob.write_bytes(b"".join(p.astype("<f4").tobytes() for p in params))
    assert blob.stat().st_size == 737 * 4, blob.stat().st_size

    fp32 = out / "denoise_tiny_v1_fp32.onnx"
    onnx.save(build_onnx(params), str(fp32))

    variants = {"a16w8": QuantType.QUInt16, "a8w8": QuantType.QUInt8}
    qdq_paths = {}
    for tag, act in variants.items():
        qdq_paths[tag] = out / f"denoise_tiny_v1_qdq_{tag}.onnx"
        cfg = get_qnn_qdq_config(str(fp32), Calib(), activation_type=act, weight_type=QuantType.QUInt8, per_channel=False)
        quantize(str(fp32), str(qdq_paths[tag]), cfg)

    # Host-side sanity (CPU EP only; says nothing about HTP).
    so = ort.SessionOptions()
    so.log_severity_level = 3
    s32 = ort.InferenceSession(str(fp32), so, providers=["CPUExecutionProvider"])
    sq = {t: ort.InferenceSession(str(pth), so, providers=["CPUExecutionProvider"]) for t, pth in qdq_paths.items()}
    ref_vs_numpy, in_vs_ref = [], []
    qdq_vs_ref = {t: [] for t in sq}
    for seed in range(5000, 5008):
        x = scene(seed)
        ref = s32.run(None, {"input": x})[0]
        ref_vs_numpy.append(psnr(ref, forward(x, params)))
        in_vs_ref.append(psnr(x, ref))
        for t, sess in sq.items():
            qdq_vs_ref[t].append(psnr(sess.run(None, {"input": x})[0], ref))
    print(f"PSNR numpy-vs-ORT-FP32 (min over 8 scenes): {min(ref_vs_numpy):.1f} dB")
    print(f"PSNR input-vs-FP32-output (discrimination, want << 35): {max(in_vs_ref):.1f} dB")
    for t, v in qdq_vs_ref.items():
        print(f"PSNR QDQ {t} (CPU EP) vs FP32 (min over 8 scenes): {min(v):.1f} dB  [host estimate only]")

    manifest = {
        "modelId": "denoise-tiny-v1",
        "description": "Untrained deterministic residual conv net for runtime validation; not a real denoiser.",
        "seed": SEED,
        "inputShape": [1, 1, H, W],
        "layers": [{"in": i, "out": o, "kernel": 3, "relu": r} for i, o, r in LAYERS],
        "residual": True,
        "targetResidualRms": TARGET_RESIDUAL_RMS,
        "files": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in (blob, fp32, *qdq_paths.values())},
        "generator": {"numpy": np.__version__, "onnx": onnx.__version__, "onnxruntime": ort.__version__},
        "hostEstimates": {
            "psnrInputVsFp32OutputMaxDb": round(max(in_vs_ref), 2),
            "psnrQdqCpuEpVsFp32MinDb": {t: round(min(v), 2) for t, v in qdq_vs_ref.items()},
            "note": "CPU EP simulation of the QDQ graphs; not HTP evidence",
        },
    }
    (out / "denoise_tiny_v1.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({k: v["bytes"] for k, v in manifest["files"].items()}))


if __name__ == "__main__":
    main()
