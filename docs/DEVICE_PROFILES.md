# Device Profiles & Capability Interrogation

## 1. Multi-Tier Strategy
The universal camera pipeline adapts dynamically to the verified capabilities of the host device:

### Tier 1 Reference Device: OnePlus 15 (12 GB RAM, Android 16)
- **SoC**: Qualcomm Snapdragon 8 Elite (SM8750) Oryon CPU, Adreno GPU, Hexagon NPU.
- **Sensors**:
  - Main: 50MP Sony LYT-808/818, f/1.6, full RAW10/12 stream support, ISO 50-25600, 1/32000s shutter.
  - Ultra-Wide: 50MP Sony LYT-600, 0.6x field of view.
  - Telephoto: 50MP Sony LYT-600, 3x periscope optical zoom.
- **Hardware Acceleration**: QNN NPU (HTP delegate), Vulkan 1.3, XNNPACK.
- **Memory Ceiling**: 512 MB Frame Ring Buffer, 2.5 GB Model Residency.

### Tier 2: Generic Flagship
- Modern Android 14+ flagship with >= 8 GB RAM, RAW sensor support, Vulkan GPU acceleration.

### Tier 3: Generic Fallback
- Standard Android device, YUV 420 streams only, CPU execution, bounded 96 MB ring buffer.
