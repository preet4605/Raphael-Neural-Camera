package com.neuralcamera.models.execution

/**
 * Explicit evidence states. "Code-complete", "tested" and "proven" are different things:
 * - [TESTED] / [NOT_TESTED] describe whether a piece of code was exercised (unit tests on the JVM count only as
 *   development evidence for logic, never for hardware behaviour).
 * - [PROVEN] / [NOT_PROVEN] describe whether a physical claim (RAW burst, HTP execution, merge advantage, ...) passed its
 *   proof gate on the target device, judged by the independent checker.
 */
enum class EvidenceState { PROVEN, NOT_PROVEN, TESTED, NOT_TESTED }

/** Where a claim's evidence comes from. Only [DEVICE_GATE_CHECKER] can make a hardware claim PROVEN. */
enum class EvidenceSource {
    /** Deterministic JVM unit test with synthetic or fixed inputs. Development evidence only. */
    JVM_UNIT_TEST,

    /** Synthetic benchmark. Development evidence only, never a product claim. */
    SYNTHETIC_BENCHMARK,

    /** A device run that was not judged by a gate checker. Observation, not proof. */
    DEVICE_OBSERVATION,

    /** A device run judged PASS by tools/proof/check_gate*.py with the required number of independent runs. */
    DEVICE_GATE_CHECKER,

    NONE
}

data class Claim(val name: String, val state: EvidenceState, val source: EvidenceSource, val note: String = "") {
    init {
        require(state != EvidenceState.PROVEN || source == EvidenceSource.DEVICE_GATE_CHECKER) {
            "$name: PROVEN requires a device run judged by the gate checker, not $source"
        }
        require(state != EvidenceState.TESTED || source != EvidenceSource.NONE) { "$name: TESTED needs a source" }
    }
}

/**
 * Which pipeline produced an output. Normal camera modes are reality-preserving (captured -> reconstructed). Generated
 * content is allowed only on [AI_STUDIO], which never mixes silently with normal capture.
 */
enum class PipelinePath {
    LIVE_PREVIEW,
    CAPTURE,
    COMPUTATIONAL_PHOTOGRAPHY,
    OUTPUT_GALLERY,
    AI_STUDIO;

    val mayGenerateContent: Boolean get() = this == AI_STUDIO
}

/** Origin of image information, tracked internally and written to processing metadata. */
enum class ContentOrigin {
    /** Sensor data as delivered by the camera (RAW, or camera-processed YUV/JPEG). */
    CAPTURED,

    /** Derived deterministically from captured data (merge, demosaic, denoise, tone mapping, restoration). */
    RECONSTRUCTED,

    /** Synthesized content not present in any capture (generative fill, style transfer, text-to-image). */
    GENERATED
}
