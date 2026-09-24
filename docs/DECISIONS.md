# Architectural Decision Records (ADR)

## ADR-001: Modular Architecture Breakdown
- **Date**: 2026-09-24
- **Decision**: Decompose the project into 13 independently testable modules (`:models`, `:device-profiles`, `:benchmarks`, `:quality-engine`, `:camera-core`, `:capture-intelligence`, `:neural-runtime`, `:neural-isp`, `:video-engine`, `:gallery`, `:ui`, `:data-lab`, `:app`).
- **Rationale**: Prevents monolithic Activities or bloated ViewModels, enforces clean boundary contracts, and isolates core runtime logic from UI presentation.

## ADR-002: Camera2 Direct Interop Over Pure CameraX Abstractions
- **Date**: 2026-09-24
- **Decision**: Treat Camera2 as the authoritative source for manual sensor exposure, RAW stream extraction, physical camera ID interrogation, and zero-shutter-lag synchronization.
- **Rationale**: Vendor-specific camera features, 50MP RAW, and sub-millisecond exposure adjustments on Tier-1 hardware (OnePlus 15) require low-level Camera2 stream configuration.

## ADR-003: Zero Fake AI & Ground-Truth Baseline Fallbacks
- **Date**: 2026-09-24
- **Decision**: Reject simulated sharpening filters labeled as "AI". Implement `BaselineImagePipeline` with true classical multi-frame temporal fusion, tone mapping, and bilateral filtering, providing a genuine baseline for neural models.
- **Rationale**: Adheres to Constitution Section 36 ("No Fake AI").

## ADR-004: Reality Guard Safety Mechanism
- **Date**: 2026-09-24
- **Decision**: Introduce `RealityGuard` and `ConfidenceEstimator` into the image reconstruction pipeline.
- **Rationale**: Neural models run the risk of hallucinating high-frequency details. High-confidence sensor regions are protected by measuring divergence and automatically blending ground truth or reverting.

## ADR-005: Selective Keyframe Neural Video Processing
- **Date**: 2026-09-24
- **Decision**: Strictly prohibit per-frame neural inference during 30/60fps video capture. Only analyze keyframes (every 15 frames) for parameter adaptation while streaming intermediate frames directly to `MediaCodec`.
- **Rationale**: Prevents thermal runaway and guaranteed dropped frames on mobile hardware (Rule 24).

## ADR-006: Non-Destructive Dual Storage (Original + Master)
- **Date**: 2026-09-24
- **Decision**: Never overwrite the original sensor capture. Save both the raw/original frame and the processed Master photograph along with JSON metadata in `OriginalMasterMediaRepository`.
- **Rationale**: Guarantees user photo preservation even under catastrophic processing failures (Rules 20, 21, Section 23).
