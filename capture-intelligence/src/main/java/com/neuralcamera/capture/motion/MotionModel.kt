package com.neuralcamera.capture.motion

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Motion and stabilization contracts: rolling-shutter timing, gyro integration over exposure windows, OIS vs EIS
 * separation, crop-aware stabilization geometry and motion confidence. Ghost rejection lives in the temporal merge
 * (TemporalMerger robust weights) and is not duplicated here.
 *
 * Gyro and frame timestamps must share one clock (CLOCK_BOOTTIME; SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME). On the
 * OnePlus 15 the timestamp source, gyro latency and OIS data availability are NOT_TESTED.
 */

/** One gyro sample in rad/s on the device axes, timestamped on the frame clock. */
data class GyroSample(val timestampNs: Long, val x: Double, val y: Double, val z: Double)

/**
 * Rolling-shutter timing for one frame. Row r starts exposing at
 * `frameStart + r * skew / (rows - 1)` and exposes for [exposureNs]. Camera2 SENSOR_TIMESTAMP is the start of exposure
 * of the first row; SENSOR_ROLLING_SHUTTER_SKEW is the first-to-last row start difference.
 */
data class RollingShutterModel(val frameStartNs: Long, val exposureNs: Long, val skewNs: Long, val rows: Int) {
    init {
        require(rows >= 1 && exposureNs >= 0 && skewNs >= 0) { "invalid rolling-shutter parameters" }
    }

    fun rowStartNs(row: Int): Long {
        require(row in 0 until rows) { "row out of range" }
        return if (rows == 1) frameStartNs else frameStartNs + skewNs * row / (rows - 1)
    }

    /** Mid-exposure time of [row]; the time a gyro rotation is attributed to for that row. */
    fun rowMidExposureNs(row: Int): Long = rowStartNs(row) + exposureNs / 2

    /** Exposure window covering every row. */
    val windowNs: LongRange get() = frameStartNs..(frameStartNs + skewNs + exposureNs)
}

data class Rotation(val x: Double, val y: Double, val z: Double) {
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
    operator fun minus(o: Rotation) = Rotation(x - o.x, y - o.y, z - o.z)
}

/** Integrates gyro rates (trapezoidal, linear interpolation at the window ends). Small-angle approximation. */
object GyroIntegrator {
    /**
     * Rotation accumulated between [fromNs] and [toNs]. Returns null when the samples do not cover the interval: missing
     * gyro data is reported as unknown, never as zero motion.
     */
    fun integrate(samples: List<GyroSample>, fromNs: Long, toNs: Long): Rotation? {
        require(toNs >= fromNs) { "interval reversed" }
        if (samples.size < 2 || samples.first().timestampNs > fromNs || samples.last().timestampNs < toNs) return null
        var rx = 0.0; var ry = 0.0; var rz = 0.0
        for (i in 0 until samples.size - 1) {
            val a = samples[i]; val b = samples[i + 1]
            val s = max(a.timestampNs, fromNs); val e = minOf(b.timestampNs, toNs)
            if (e <= s) continue
            val sa = interp(a, b, s); val sb = interp(a, b, e)
            val dt = (e - s) / 1e9
            rx += (sa[0] + sb[0]) / 2 * dt; ry += (sa[1] + sb[1]) / 2 * dt; rz += (sa[2] + sb[2]) / 2 * dt
        }
        return Rotation(rx, ry, rz)
    }

    private fun interp(a: GyroSample, b: GyroSample, t: Long): DoubleArray {
        val span = (b.timestampNs - a.timestampNs).toDouble()
        val f = if (span <= 0) 0.0 else (t - a.timestampNs) / span
        return doubleArrayOf(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
    }
}

/** Which stabilization acted on a frame. OIS moves the lens/sensor optically; EIS crops/warps the digital image. */
data class StabilizationContext(
    /** LENS_OPTICAL_STABILIZATION_MODE == ON in the result. Null = key absent. */
    val oisActive: Boolean?,
    /** CONTROL_VIDEO_STABILIZATION_MODE != OFF. EIS output is warped and must not be treated as sensor geometry. */
    val eisActive: Boolean?,
    /** Whether STATISTICS_OIS_DATA shifts were delivered (needed to subtract OIS from gyro motion). */
    val oisSamplesAvailable: Boolean,
    /** Output crop on the active array (left, top, width, height) after zoom/EIS. */
    val cropRegion: IntArray,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int
) {
    /** Gyro motion maps to image motion only when EIS did not warp the frame and OIS is either off or measured. */
    val geometryIsSensorAligned: Boolean
        get() = eisActive == false && (oisActive == false || oisSamplesAvailable)

    /** Pixels of the output per pixel of the active array (crop magnification). */
    val cropScale: Double get() = activeArrayWidth.toDouble() / cropRegion[2]
}

enum class MotionClass { STILL, HANDHELD, MOVING, UNKNOWN }

data class MotionEstimate(
    /** Camera rotation during the exposure, converted to output pixels. Null when gyro coverage is missing. */
    val blurPx: Double?,
    /** Rotation between two frames' mid-exposure times in output pixels (inter-frame shift for alignment seeding). */
    val interFrameShiftPx: Double?,
    val motionClass: MotionClass,
    /** 0..1: how far the gyro-derived numbers can be trusted for this frame's geometry. */
    val confidence: Double,
    val reasons: List<String>
)

/**
 * Converts gyro rotation into image-space motion for one frame. [focalLengthPx] is the focal length in active-array
 * pixels (LENS_INTRINSIC_CALIBRATION fx, or focal length / pixel pitch).
 */
object MotionEstimator {
    const val STILL_BLUR_PX = 0.5
    const val MOVING_BLUR_PX = 4.0

    fun estimate(
        gyro: List<GyroSample>,
        frame: RollingShutterModel,
        previousFrame: RollingShutterModel?,
        stabilization: StabilizationContext,
        focalLengthPx: Double
    ): MotionEstimate {
        val reasons = ArrayList<String>()
        val scale = focalLengthPx * stabilization.cropScale
        val during = GyroIntegrator.integrate(gyro, frame.windowNs.first, frame.windowNs.last)
        val blur = during?.let { angleToPx(it.magnitude, scale) }
        if (during == null) reasons.add("gyro samples do not cover the exposure window")
        val shift = if (previousFrame != null) {
            val mid = frame.rowMidExposureNs(frame.rows / 2)
            val prevMid = previousFrame.rowMidExposureNs(previousFrame.rows / 2)
            GyroIntegrator.integrate(gyro, prevMid, mid)?.let { angleToPx(it.magnitude, scale) }
                ?: run { reasons.add("gyro samples do not cover the inter-frame interval"); null }
        } else null

        var confidence = 1.0
        if (during == null) confidence = 0.0
        if (stabilization.eisActive != false) { confidence *= 0.3; reasons.add("EIS active or unknown: output geometry is warped") }
        if (stabilization.oisActive != false && !stabilization.oisSamplesAvailable) {
            confidence *= 0.5; reasons.add("OIS active or unknown without OIS samples: lens shift not subtracted")
        }
        val cls = when {
            blur == null -> MotionClass.UNKNOWN
            blur < STILL_BLUR_PX -> MotionClass.STILL
            blur < MOVING_BLUR_PX -> MotionClass.HANDHELD
            else -> MotionClass.MOVING
        }
        return MotionEstimate(blur, shift, cls, confidence, reasons)
    }

    private fun angleToPx(angleRad: Double, focalPx: Double): Double = abs(focalPx * kotlin.math.tan(angleRad))

    /** Horizontal field of view helper for tests and profile sanity checks. */
    fun horizontalFovRad(focalLengthPx: Double, widthPx: Int): Double = 2 * atan(widthPx / (2 * focalLengthPx))
}
