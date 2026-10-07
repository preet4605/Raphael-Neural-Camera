package com.neuralcamera.capture.policy

import com.neuralcamera.capture.CameraShootingMode
import com.neuralcamera.models.execution.ContentOrigin

/*
 * Quality policies adapt scheduling (frame counts, budgets, which optional stages run, backend preference) without
 * changing image semantics: every policy produces captured -> reconstructed output and none may enable generated
 * content. The numbers are design defaults; their latency/thermal/battery effect on the OnePlus 15 is NOT_TESTED.
 */

enum class QualityPolicyId { INSTANT, BALANCED, MAXIMUM, NIGHT, THERMAL, BATTERY_SAVER }

enum class BackendPreference {
    /** Classical CPU stages only. */
    CPU_ONLY,

    /** Use an accelerator only when its model is VERIFIED on this device (Gate 2); otherwise CPU. */
    VERIFIED_ACCELERATOR_ELSE_CPU
}

data class SchedulingPolicy(
    val id: QualityPolicyId,
    val maxFrames: Int,
    val minFrames: Int,
    /** Wall-clock budget for capture + processing before the pipeline degrades to fewer frames. */
    val processingBudgetMs: Long,
    val convergenceTimeoutMs: Long,
    val allowExposureBracketing: Boolean,
    val allowNeuralRestoration: Boolean,
    val backend: BackendPreference,
    /** Fraction of full resolution processed by the merge (1.0 = full). */
    val processingScale: Double,
    /** What the output may contain. Always CAPTURED + RECONSTRUCTED for camera policies. */
    val permittedOrigins: Set<ContentOrigin> = REALITY_PRESERVING
) {
    init {
        require(minFrames in 1..maxFrames) { "minFrames must be within 1..maxFrames" }
        require(processingScale > 0.0 && processingScale <= 1.0) { "processingScale must be in (0, 1]" }
        require(ContentOrigin.GENERATED !in permittedOrigins) { "camera policies never permit generated content" }
    }

    companion object {
        val REALITY_PRESERVING = setOf(ContentOrigin.CAPTURED, ContentOrigin.RECONSTRUCTED)
    }
}

/** Device conditions a policy may react to. Null = not measured (treated conservatively). */
data class DeviceConditions(
    /** PowerManager thermal status 0..6 (THERMAL_STATUS_NONE..SHUTDOWN). */
    val thermalStatus: Int?,
    val batteryPercent: Int?,
    val powerSaveMode: Boolean?
)

object QualityPolicies {
    fun of(id: QualityPolicyId): SchedulingPolicy = when (id) {
        QualityPolicyId.INSTANT -> SchedulingPolicy(id, 1, 1, 300, 300, false, false, BackendPreference.CPU_ONLY, 1.0)
        QualityPolicyId.BALANCED -> SchedulingPolicy(id, 6, 2, 3_000, 1_000, false, false, BackendPreference.VERIFIED_ACCELERATOR_ELSE_CPU, 1.0)
        QualityPolicyId.MAXIMUM -> SchedulingPolicy(id, 12, 3, 10_000, 1_500, true, true, BackendPreference.VERIFIED_ACCELERATOR_ELSE_CPU, 1.0)
        QualityPolicyId.NIGHT -> SchedulingPolicy(id, 15, 4, 15_000, 2_000, false, true, BackendPreference.VERIFIED_ACCELERATOR_ELSE_CPU, 1.0)
        QualityPolicyId.THERMAL -> SchedulingPolicy(id, 3, 1, 2_000, 800, false, false, BackendPreference.CPU_ONLY, 0.5)
        QualityPolicyId.BATTERY_SAVER -> SchedulingPolicy(id, 3, 1, 2_000, 800, false, false, BackendPreference.CPU_ONLY, 1.0)
    }

    /**
     * The policy actually applied: the requested one, overridden by THERMAL when the device is hot (status >= SEVERE)
     * and by BATTERY_SAVER when power-save is on or the battery is low. Overrides only shrink work; the override reason
     * is returned so the processing metadata records it.
     */
    fun resolve(requested: QualityPolicyId, conditions: DeviceConditions): Pair<SchedulingPolicy, String?> {
        val thermal = conditions.thermalStatus
        if (thermal != null && thermal >= 3 && requested != QualityPolicyId.INSTANT) {
            return of(QualityPolicyId.THERMAL) to "thermal status $thermal >= SEVERE overrides $requested"
        }
        val lowBattery = conditions.powerSaveMode == true || (conditions.batteryPercent ?: 100) <= 15
        if (lowBattery && requested !in setOf(QualityPolicyId.INSTANT, QualityPolicyId.THERMAL, QualityPolicyId.BATTERY_SAVER)) {
            return of(QualityPolicyId.BATTERY_SAVER) to "power saving (powerSave=${conditions.powerSaveMode}, battery=${conditions.batteryPercent}%) overrides $requested"
        }
        return of(requested) to null
    }

    /** Policy each shooting mode requests ([ModeBehaviours]). */
    fun requestedFor(mode: CameraShootingMode): QualityPolicyId = ModeBehaviours.of(mode).policy

    /**
     * Applies device conditions to a planned burst. Without an override the planner's frame count stands; with a
     * thermal or power override it is capped at the override policy's maximum (overrides only ever shrink work).
     */
    fun decide(plannedFrames: Int, mode: CameraShootingMode, conditions: DeviceConditions): PolicyDecision {
        require(plannedFrames >= 1) { "at least one planned frame" }
        val (policy, override) = resolve(requestedFor(mode), conditions)
        val frames = if (override != null) minOf(plannedFrames, policy.maxFrames) else plannedFrames
        return PolicyDecision(policy, override, frames)
    }
}

data class PolicyDecision(val policy: SchedulingPolicy, val override: String?, val frames: Int)
