# Privacy & Local Data Protection

## 1. Zero Cloud Transmission Invariant (Rules 5, 6, 28)
Neural Camera is strictly an offline, rootless computational photography engine:
- **No Cloud Upload**: Photos, videos, raw Bayer frames, gyro telemetry, and metadata are processed and stored strictly on local device storage.
- **No network, including from dependencies**: ONNX Runtime's Android AAR ships a telemetry client plus `INTERNET`/`ACCESS_NETWORK_STATE` permissions. The app manifest removes the telemetry provider and both permissions, and the runtime calls `OrtEnvironment.setTelemetry(false)`. Verify the merged manifest of any build before release.
- **Local Accelerator Execution**: All perception, capture planning, and neural reconstruction executes locally on Qualcomm Hexagon NPU, Adreno GPU, or ARM CPU.
- **Scoped Storage Isolation**: Media is committed through Android `MediaStore` and scoped internal directories.

## 2. Telemetry and Logging Sanitation
- Telemetry loggers (`TelemetryLogger`, `BenchmarkRunner`) record only execution durations, memory deltas, thermal states, and hardware capability flags.
- **Pixel Data Redaction**: Log files and diagnostic dumps never contain pixel buffers, raw images, or user identifiable metadata.
- Diagnostic exports require explicit user toggle in settings or debug instrumentation.
