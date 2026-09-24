# Empirical Benchmarking Framework

## 1. Metrics Protocol
In accordance with Rule 32 ("Every major optimization must be benchmarked") and Rule 35 ("Never report an improvement without a baseline"):
- All pipeline stages report latency (median, p95, p99), heap allocation delta, and thermal delta.
- Empirical telemetry avoids subjective evaluation.

## 2. SLA Latency & Memory Targets
| Pipeline Stage | Target Latency (p95) | Max Heap Allocation | Target Accuracy / PSNR |
|---|---|---|---|
| Frame Acquisition & Sync | $\le 2\text{ ms}$ | $0\text{ bytes}$ (recycled) | 100% frame metadata sync |
| Scene & Motion Analysis | $\le 5\text{ ms}$ | $< 1\text{ MB}$ | Angular velocity $\pm 0.01\text{ rad/s}$ |
| Classical Baseline ISP | $\le 30\text{ ms}$ | $< 32\text{ MB}$ | PSNR $\ge 35\text{ dB}$ |
| Neural ISP Lite (HTP NPU) | $\le 45\text{ ms}$ | $< 96\text{ MB}$ | PSNR $\ge 40\text{ dB}$ |
| Reality Guard Inspection | $\le 8\text{ ms}$ | $< 4\text{ MB}$ | $0\%$ unflagged hallucinations |
| Video Keyframe Stabilization | $\le 4\text{ ms}$ | $< 8\text{ MB}$ | Stable 30/60 fps presentation |
