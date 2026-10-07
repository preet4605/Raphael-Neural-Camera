package com.neuralcamera.runtime.registry

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelCompatibilityState
import com.neuralcamera.models.ModelVersion
import com.neuralcamera.models.TensorPrecision
import java.security.MessageDigest

/*
 * Model management: versioned artifact records with checksums, precision/quantization, backend, memory cost,
 * compatibility evidence, warm/cold load state, cache and rollback metadata.
 *
 * Compatibility is evidence-driven. A model may be VERIFIED for a backend only with a passing on-device Gate 2 record for
 * that exact artifact checksum and runtime stack. Nothing in this repository is VERIFIED today.
 */

/** The runtime stack an artifact is executed with. A verification never transfers to a different stack. */
data class RuntimeStack(
    val backend: HardwareBackendType,
    /** e.g. "onnxruntime-android-qnn 1.29.0". */
    val runtime: String,
    /** QNN runtime actually loaded by the app (the Maven-bundled one), e.g. "2.42.0"; null for non-QNN stacks. */
    val bundledQnnVersion: String?,
    /** QNN version the device firmware ships (OnePlus 15: 2.37.4); recorded because the HTP skel/driver pairing matters. */
    val deviceQnnVersion: String?,
    /** HTP architecture targeted, e.g. "v81". */
    val htpArch: String?
)

enum class QnnPairing {
    /** Not a QNN stack. */
    NOT_APPLICABLE,

    /** Bundled and device QNN versions match. Still needs Gate 2 evidence to be usable. */
    MATCHED,

    /** Versions differ (e.g. bundled 2.42.0 vs device 2.37.4). Compatibility UNVERIFIED until a device run proves it. */
    MISMATCHED_UNVERIFIED,

    /** A version is unknown. */
    UNKNOWN
}

fun RuntimeStack.qnnPairing(): QnnPairing = when {
    backend != HardwareBackendType.QUALCOMM_QNN_NPU -> QnnPairing.NOT_APPLICABLE
    bundledQnnVersion == null || deviceQnnVersion == null -> QnnPairing.UNKNOWN
    bundledQnnVersion == deviceQnnVersion -> QnnPairing.MATCHED
    else -> QnnPairing.MISMATCHED_UNVERIFIED
}

/** A Gate 2 (or equivalent) verdict for one artifact on one device and stack, as judged by tools/proof/check_gate2.py. */
data class VerificationRecord(
    val artifactSha256: String,
    val deviceModel: String,
    val stack: RuntimeStack,
    val checkerPassed: Boolean,
    val independentRuns: Int,
    val measuredP95Ms: Double?,
    val measuredPeakMemoryBytes: Long?,
    val reportPath: String
)

data class ModelArtifact(
    val modelId: String,
    val version: ModelVersion,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val precision: TensorPrecision,
    /** e.g. "qdq-a16w8", "qdq-a8w8", "fp32". */
    val quantization: String,
    val stack: RuntimeStack,
    /** Measured memory cost; null until measured on the device. */
    val measuredMemoryBytes: Long? = null,
    val verification: VerificationRecord? = null
) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be 64 lowercase hex chars" }
        require(sizeBytes > 0) { "artifact size must be positive" }
        require(verification == null || verification.artifactSha256 == sha256) { "verification is for a different artifact" }
    }

    /**
     * Derived compatibility. VERIFIED needs a checker-passed record with >= 3 independent runs on this exact stack;
     * anything else is UNVERIFIED (or UNSUPPORTED when the checker failed it).
     */
    val compatibility: ModelCompatibilityState
        get() {
            val v = verification ?: return ModelCompatibilityState.UNVERIFIED
            if (v.stack != stack) return ModelCompatibilityState.UNVERIFIED
            if (!v.checkerPassed) return ModelCompatibilityState.UNSUPPORTED
            return if (v.independentRuns >= 3) ModelCompatibilityState.VERIFIED else ModelCompatibilityState.UNVERIFIED
        }
}

enum class LoadState { NOT_LOADED, LOADING_COLD, WARM, FAILED, EVICTED }

data class LoadRecord(val state: LoadState, val coldLoadMs: Long?, val lastError: String?)

sealed class ActivationResult {
    data class Activated(val artifact: ModelArtifact) : ActivationResult()
    data class Rejected(val reason: String) : ActivationResult()
}

/**
 * Registry of model artifacts per model id. Holds every installed version, the active one, a rollback target, load state
 * and a cache key. Thread-safe by synchronization; it is small and rarely contended.
 */
class ModelLifecycleRegistry {
    private val versions = HashMap<String, MutableList<ModelArtifact>>()
    private val active = HashMap<String, ModelArtifact>()
    private val previous = HashMap<String, ModelArtifact>()
    private val loads = HashMap<String, LoadRecord>()

    @Synchronized
    fun install(artifact: ModelArtifact) {
        val list = versions.getOrPut(artifact.modelId) { ArrayList() }
        require(list.none { it.version == artifact.version && it.sha256 != artifact.sha256 }) {
            "${artifact.modelId} ${artifact.version} is already installed with a different checksum"
        }
        if (list.none { it.sha256 == artifact.sha256 }) list.add(artifact)
    }

    /**
     * Activates [version] after checking [bytes] against the recorded checksum and size. Accelerator stacks also require
     * VERIFIED compatibility unless [allowUnverifiedForProbe] (only proof harnesses may set it).
     */
    @Synchronized
    fun activate(modelId: String, version: ModelVersion, bytes: ByteArray, allowUnverifiedForProbe: Boolean = false): ActivationResult {
        val a = versions[modelId]?.firstOrNull { it.version == version } ?: return ActivationResult.Rejected("$modelId $version is not installed")
        if (bytes.size.toLong() != a.sizeBytes) return ActivationResult.Rejected("size ${bytes.size} != recorded ${a.sizeBytes}")
        val actual = sha256(bytes)
        if (actual != a.sha256) return ActivationResult.Rejected("checksum mismatch: $actual != ${a.sha256}")
        val accelerator = a.stack.backend !in setOf(HardwareBackendType.CPU_REFERENCE, HardwareBackendType.ORT_CPU)
        if (accelerator && a.compatibility != ModelCompatibilityState.VERIFIED && !allowUnverifiedForProbe) {
            return ActivationResult.Rejected(
                "$modelId $version on ${a.stack.backend} is ${a.compatibility} (QNN pairing ${a.stack.qnnPairing()}); " +
                    "only a passing on-device Gate 2 record can enable it"
            )
        }
        active[modelId]?.let { if (it.sha256 != a.sha256) previous[modelId] = it }
        active[modelId] = a
        loads[modelId] = LoadRecord(LoadState.NOT_LOADED, null, null)
        return ActivationResult.Activated(a)
    }

    @Synchronized
    fun active(modelId: String): ModelArtifact? = active[modelId]

    @Synchronized
    fun rollbackTarget(modelId: String): ModelArtifact? = previous[modelId]

    /** Reverts to the previously active version. Returns it, or null when there is nothing to roll back to. */
    @Synchronized
    fun rollback(modelId: String, reason: String): ModelArtifact? {
        val prev = previous.remove(modelId) ?: return null
        active[modelId] = prev
        loads[modelId] = LoadRecord(LoadState.NOT_LOADED, null, "rolled back: $reason")
        return prev
    }

    @Synchronized
    fun recordLoad(modelId: String, state: LoadState, coldLoadMs: Long? = null, error: String? = null) {
        require(active.containsKey(modelId)) { "$modelId has no active artifact" }
        require(state != LoadState.FAILED || error != null) { "a failed load must carry its error" }
        loads[modelId] = LoadRecord(state, coldLoadMs, error)
    }

    @Synchronized
    fun loadRecord(modelId: String): LoadRecord? = loads[modelId]

    /** Cache key for compiled/serialized contexts: changes whenever the artifact or the runtime stack changes. */
    fun cacheKey(a: ModelArtifact): String =
        sha256("${a.sha256}|${a.stack.backend}|${a.stack.runtime}|${a.stack.bundledQnnVersion}|${a.stack.htpArch}".toByteArray()).take(16)

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The QNN stack this repository builds today: ORT 1.29.0 pulls com.qualcomm.qti:qnn-runtime 2.42.0. */
        val CURRENT_QNN_STACK = RuntimeStack(
            backend = HardwareBackendType.QUALCOMM_QNN_NPU,
            runtime = "onnxruntime-android-qnn 1.29.0",
            bundledQnnVersion = "2.42.0",
            deviceQnnVersion = "2.37.4",
            htpArch = "v81"
        )
    }
}
