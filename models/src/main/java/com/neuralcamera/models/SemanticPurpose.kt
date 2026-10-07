package com.neuralcamera.models

/**
 * Defines the functional semantic role of on-device neural models.
 * Strictly enforces that models are segregated by purpose (e.g. real-time ISP vs generative).
 */
enum class SemanticPurpose {
    /** Scene understanding, subject segmentation, depth, blur, and motion estimation. */
    PERCEPTION,

    /** Exposure planning, focus planning, temporal depth calculation, lens selection. */
    CAPTURE_INTELLIGENCE,

    /** Core image reconstruction: demosaic, multi-frame denoising, HDR reconstruction, deblur, optical correction. */
    NEURAL_ISP,

    /** Temporal alignment, multi-frame optical flow fusion, confidence estimation across time. */
    TEMPORAL_RECONSTRUCTION,

    /** Heavy post-capture restoration: super-resolution, difficult deblur, artifact mitigation. */
    HEAVY_RESTORATION,

    /** Optional AI Studio generative workflows (isolated outside of real-time camera hot path). */
    GENERATIVE,

    /** Optional high-level semantic director (natural language assistance, scene reasoning). */
    SEMANTIC_DIRECTOR,

    /** Deterministic test networks used only to validate runtime numerics, latency and backend attribution. Never in the camera path. */
    RUNTIME_VALIDATION
}
