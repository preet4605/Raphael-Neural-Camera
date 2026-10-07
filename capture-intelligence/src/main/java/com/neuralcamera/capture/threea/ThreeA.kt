package com.neuralcamera.capture.threea

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * Camera 3A contracts: AE/AF/AWB states, convergence detection, metering regions, exposure bracketing, manual<->auto
 * transitions and per-frame exposure records.
 *
 * Pure Kotlin. The integer codes below are the documented values of android.hardware.camera2.CaptureResult
 * CONTROL_AE_STATE / CONTROL_AF_STATE / CONTROL_AWB_STATE, so a Camera2 adapter maps results with fromCamera2(...).
 * No Camera2 adapter is wired yet: RealCamera2Controller runs bursts on CONTROL_MODE_AUTO without waiting for
 * convergence. Physical behaviour on the OnePlus 15 (how fast and how reliably 3A converges): NOT_TESTED.
 */

enum class AeState(val camera2: Int) {
    INACTIVE(0), SEARCHING(1), CONVERGED(2), LOCKED(3), FLASH_REQUIRED(4), PRECAPTURE(5);

    companion object {
        /** Null for a missing key; unknown codes are reported as null, never guessed. */
        fun fromCamera2(code: Int?): AeState? = entries.firstOrNull { it.camera2 == code }
    }
}

enum class AfState(val camera2: Int) {
    INACTIVE(0), PASSIVE_SCAN(1), PASSIVE_FOCUSED(2), ACTIVE_SCAN(3), FOCUSED_LOCKED(4), NOT_FOCUSED_LOCKED(5),
    PASSIVE_UNFOCUSED(6);

    companion object {
        fun fromCamera2(code: Int?): AfState? = entries.firstOrNull { it.camera2 == code }
    }
}

enum class AwbState(val camera2: Int) {
    INACTIVE(0), SEARCHING(1), CONVERGED(2), LOCKED(3);

    companion object {
        fun fromCamera2(code: Int?): AwbState? = entries.firstOrNull { it.camera2 == code }
    }
}

/** Per-frame exposure facts, strictly from the capture result. Null = the result did not carry the key. */
data class ExposureRecord(
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    /** CONTROL_POST_RAW_SENSITIVITY_BOOST (100 = none). */
    val postRawBoost: Int?,
    val frameDurationNs: Long?,
    val aperture: Float?,
    val aeState: AeState?,
    val afState: AfState?,
    val awbState: AwbState?,
    val aeLocked: Boolean?,
    val awbLocked: Boolean?,
    /** LENS_FOCUS_DISTANCE in diopters. */
    val focusDistanceDiopters: Float?,
    /** True when the request was manual (CONTROL_AE_MODE_OFF); auto results reflect the AE's own choice. */
    val manualExposure: Boolean
) {
    /** Exposure time x analog/digital gain, in ISO-seconds; null when either fact is missing. */
    val exposureProduct: Double?
        get() {
            val t = exposureTimeNs ?: return null
            val iso = sensitivityIso ?: return null
            val boost = (postRawBoost ?: 100) / 100.0
            return t / 1e9 * iso * boost
        }
}

enum class ConvergenceVerdict {
    /** AE/AF/AWB settled and exposure stable over the window. */
    CONVERGED,

    /** Still settling. */
    PENDING,

    /** Deadline passed without convergence. Capture may proceed but the frames must be flagged as not converged. */
    TIMED_OUT,

    /** AF reports it cannot focus (NOT_FOCUSED_LOCKED / PASSIVE_UNFOCUSED). Capture may proceed, flagged. */
    FOCUS_FAILED,

    /** The result stream does not carry 3A state keys; convergence cannot be judged. Never treated as converged. */
    UNKNOWN
}

data class ConvergenceParams(
    /** Consecutive frames whose exposure product must agree before AE counts as stable. */
    val stableFrames: Int = 3,
    /** Allowed relative spread of the exposure product across the stable window. */
    val exposureTolerance: Double = 0.05,
    val requireFocus: Boolean = true,
    val timeoutNs: Long = 1_500_000_000L
)

/**
 * Decides when 3A has converged from a stream of [ExposureRecord]s. State codes alone are not enough: some HALs report
 * CONVERGED while exposure still moves, so the exposure product must also be stable over [ConvergenceParams.stableFrames].
 */
class ConvergenceDetector(private val params: ConvergenceParams = ConvergenceParams()) {
    private val window = ArrayDeque<ExposureRecord>()
    private var startNs: Long? = null

    fun reset() {
        window.clear(); startNs = null
    }

    fun onResult(record: ExposureRecord): ConvergenceVerdict {
        if (startNs == null) startNs = record.sensorTimestampNs
        window.addLast(record)
        while (window.size > params.stableFrames) window.removeFirst()
        val verdict = evaluate()
        if (verdict == ConvergenceVerdict.PENDING &&
            record.sensorTimestampNs - (startNs ?: record.sensorTimestampNs) >= params.timeoutNs
        ) return ConvergenceVerdict.TIMED_OUT
        return verdict
    }

    private fun evaluate(): ConvergenceVerdict {
        val last = window.last()
        if (last.aeState == null || last.awbState == null || (params.requireFocus && last.afState == null)) {
            return ConvergenceVerdict.UNKNOWN
        }
        if (params.requireFocus && (last.afState == AfState.NOT_FOCUSED_LOCKED || last.afState == AfState.PASSIVE_UNFOCUSED)) {
            return ConvergenceVerdict.FOCUS_FAILED
        }
        val aeOk = last.manualExposure || last.aeState in AE_SETTLED
        val awbOk = last.awbState in AWB_SETTLED
        val afOk = !params.requireFocus || last.afState in AF_SETTLED
        if (!(aeOk && awbOk && afOk)) return ConvergenceVerdict.PENDING
        if (window.size < params.stableFrames) return ConvergenceVerdict.PENDING
        val products = window.map { it.exposureProduct ?: return ConvergenceVerdict.UNKNOWN }
        val mean = products.average()
        if (mean <= 0.0) return ConvergenceVerdict.UNKNOWN
        val spread = (products.max() - products.min()) / mean
        return if (spread <= params.exposureTolerance) ConvergenceVerdict.CONVERGED else ConvergenceVerdict.PENDING
    }

    companion object {
        val AE_SETTLED = setOf(AeState.CONVERGED, AeState.LOCKED, AeState.FLASH_REQUIRED)
        val AWB_SETTLED = setOf(AwbState.CONVERGED, AwbState.LOCKED)
        val AF_SETTLED = setOf(AfState.FOCUSED_LOCKED, AfState.PASSIVE_FOCUSED)
    }
}

/** A metering region in normalized [0,1] preview coordinates, mapped to the sensor active array for Camera2. */
data class MeteringRegion(val left: Double, val top: Double, val right: Double, val bottom: Double, val weight: Int) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0) { "coordinates must be normalized" }
        require(right > left && bottom > top) { "empty region" }
        require(weight in 0..1000) { "Camera2 metering weight is 0..1000" }
    }

    /**
     * Pixel rectangle on the active array (left, top, width, height), after removing the zoom crop. [cropLeft]/[cropTop]
     * /[cropWidth]/[cropHeight] is the SCALER_CROP_REGION (or the active array when unzoomed).
     */
    fun toActiveArray(cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int): IntArray {
        val x0 = cropLeft + (left * cropWidth).toInt()
        val y0 = cropTop + (top * cropHeight).toInt()
        val x1 = cropLeft + (right * cropWidth).toInt()
        val y1 = cropTop + (bottom * cropHeight).toInt()
        return intArrayOf(x0, y0, maxOf(1, x1 - x0), maxOf(1, y1 - y0))
    }

    companion object {
        /** Centre-weighted spot of [size] (fraction of the frame) around a tap point, clamped to the frame. */
        fun around(cx: Double, cy: Double, size: Double = 0.1, weight: Int = 1000): MeteringRegion {
            val h = size / 2
            val l = (cx - h).coerceIn(0.0, 1.0 - size)
            val t = (cy - h).coerceIn(0.0, 1.0 - size)
            return MeteringRegion(l, t, l + size, t + size, weight)
        }
    }
}

/** Sensor limits from CameraCharacteristics. Never defaulted: a missing range means manual exposure is unsupported. */
data class ExposureLimits(
    val exposureTimeRangeNs: LongRange,
    val sensitivityRange: IntRange,
    /** Longest exposure the plan may use for hand-held capture (motion blur budget), not a sensor limit. */
    val handheldMaxExposureNs: Long = 66_000_000L
)

data class ExposureSetting(val exposureTimeNs: Long, val iso: Int, val evOffset: Double, val clamped: Boolean)

/**
 * Exposure bracketing: turns EV offsets around a converged base exposure into explicit manual settings. Time is changed
 * first (up to the hand-held limit), then ISO. A step the sensor cannot reach is clamped and flagged, never silently
 * reported as reached.
 */
object ExposureBracketPlanner {
    fun plan(base: ExposureRecord, evSteps: List<Double>, limits: ExposureLimits): List<ExposureSetting> {
        val t0 = requireNotNull(base.exposureTimeNs) { "base exposure time is unknown" }
        val iso0 = requireNotNull(base.sensitivityIso) { "base ISO is unknown" }
        return evSteps.map { ev ->
            val target = t0.toDouble() * iso0 * 2.0.pow(ev)
            val maxT = minOf(limits.exposureTimeRangeNs.last, limits.handheldMaxExposureNs)
            var t = (target / iso0).coerceIn(limits.exposureTimeRangeNs.first.toDouble(), maxT.toDouble())
            var iso = (target / t).coerceIn(limits.sensitivityRange.first.toDouble(), limits.sensitivityRange.last.toDouble())
            // If ISO hit its floor, shorten time further (bright brackets).
            if (t * iso > target * 1.0001) t = (target / iso).coerceIn(limits.exposureTimeRangeNs.first.toDouble(), maxT.toDouble())
            val achieved = t * iso
            val clamped = abs(ln(achieved / target)) > ln(1.02)
            iso = iso.coerceIn(limits.sensitivityRange.first.toDouble(), limits.sensitivityRange.last.toDouble())
            ExposureSetting(t.roundToLong(), iso.toInt(), ev, clamped)
        }
    }
}

enum class ExposureControlMode { AUTO, MANUAL }

/**
 * Manual<->auto transitions. Switching to manual seeds the manual values from the last converged auto result so the
 * image does not jump; switching back to auto releases the locks and restarts convergence.
 */
class ExposureModeController(private val limits: ExposureLimits?) {
    var mode: ExposureControlMode = ExposureControlMode.AUTO
        private set
    var manual: ExposureSetting? = null
        private set
    private var lastConverged: ExposureRecord? = null

    fun onAutoResult(record: ExposureRecord, verdict: ConvergenceVerdict) {
        if (mode == ExposureControlMode.AUTO && verdict == ConvergenceVerdict.CONVERGED) lastConverged = record
    }

    /** Returns false (and stays in AUTO) when the camera has no manual exposure range or no converged seed exists. */
    fun switchToManual(): Boolean {
        val lim = limits ?: return false
        val seed = lastConverged ?: return false
        val t = seed.exposureTimeNs ?: return false
        val iso = seed.sensitivityIso ?: return false
        manual = ExposureSetting(
            t.coerceIn(lim.exposureTimeRangeNs.first, lim.exposureTimeRangeNs.last),
            iso.coerceIn(lim.sensitivityRange.first, lim.sensitivityRange.last), 0.0, clamped = false
        )
        mode = ExposureControlMode.MANUAL
        return true
    }

    fun setManual(exposureTimeNs: Long, iso: Int): ExposureSetting {
        check(mode == ExposureControlMode.MANUAL) { "not in manual mode" }
        val lim = limits!!
        val t = exposureTimeNs.coerceIn(lim.exposureTimeRangeNs.first, lim.exposureTimeRangeNs.last)
        val i = iso.coerceIn(lim.sensitivityRange.first, lim.sensitivityRange.last)
        return ExposureSetting(t, i, 0.0, clamped = t != exposureTimeNs || i != iso).also { manual = it }
    }

    /** Back to auto: manual values are dropped; the caller must reset its [ConvergenceDetector]. */
    fun switchToAuto() {
        mode = ExposureControlMode.AUTO
        manual = null
        lastConverged = null
    }
}
