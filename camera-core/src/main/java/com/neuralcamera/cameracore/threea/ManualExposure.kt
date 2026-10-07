package com.neuralcamera.cameracore.threea

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Manual exposure for PRO: the selectable values are the standard 1/3-stop series clipped to the ranges the camera
 * reported (SENSOR_INFO_SENSITIVITY_RANGE, SENSOR_INFO_EXPOSURE_TIME_RANGE), and every captured frame is checked
 * against what was requested, because a HAL may round, clamp or ignore a manual request.
 */
object ManualStops {
    private val ISO = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
        4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400
    )

    /** Shutter speeds in seconds, 1/3 stops, as cameras label them. */
    private val SHUTTER_S = doubleArrayOf(
        1 / 16000.0, 1 / 12800.0, 1 / 10000.0, 1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0, 1 / 2500.0,
        1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0, 1 / 500.0, 1 / 400.0, 1 / 320.0, 1 / 250.0,
        1 / 200.0, 1 / 160.0, 1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0,
        1 / 15.0, 1 / 13.0, 1 / 10.0, 1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0,
        2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0
    )

    fun iso(range: IntRange): List<Int> = ISO.filter { it in range }

    fun shutterNs(range: LongRange): List<Long> = SHUTTER_S.map { (it * 1e9).roundToLong() }.filter { it in range }

    fun shutterLabel(ns: Long): String {
        val s = ns / 1e9
        return if (s < 0.28) "1/${Math.round(1 / s)}" else if (s == Math.floor(s)) "${s.toLong()}s" else "${s}s"
    }

    /** Index of the value closest to [value] in stops (log scale); -1 for an empty list. */
    fun nearest(values: List<Long>, value: Long): Int =
        values.indices.minByOrNull { abs(ln(values[it].toDouble() / value)) } ?: -1
}

enum class ManualOutcome {
    /** Result reports manual exposure with the requested time and ISO (within [ManualFrameCheck.TOLERANCE]). */
    APPLIED,
    /** Result reports values that differ from the request (rounded beyond tolerance, clamped, or AE stayed on). */
    DEVIATED,
    /** Result lacks the fields needed to tell. */
    NOT_REPORTED
}

data class ManualFrameCheck(val frameNumber: Long, val outcome: ManualOutcome, val detail: String) {
    companion object {
        /** Relative tolerance: HALs quantize exposure to sensor line time and gain to their own steps. */
        const val TOLERANCE = 0.03

        fun of(requested: ExposureSetting, result: ExposureRecord): ManualFrameCheck {
            val t = result.exposureTimeNs
            val iso = result.sensitivityIso
            if (t == null || iso == null) return ManualFrameCheck(result.frameNumber, ManualOutcome.NOT_REPORTED, "exposure time or ISO missing from the result")
            fun off(a: Double, b: Double) = abs(a / b - 1) > TOLERANCE
            val problems = buildList {
                if (!result.manualExposure) add("AE was not off")
                if (off(t.toDouble(), requested.exposureTimeNs.toDouble())) add("time ${t} ns vs requested ${requested.exposureTimeNs} ns")
                if (off(iso.toDouble(), requested.iso.toDouble())) add("ISO $iso vs requested ${requested.iso}")
                val boost = result.postRawBoost
                if (boost != null && boost != 100) add("post-RAW boost $boost")
            }
            return if (problems.isEmpty()) ManualFrameCheck(result.frameNumber, ManualOutcome.APPLIED, "time $t ns, ISO $iso")
            else ManualFrameCheck(result.frameNumber, ManualOutcome.DEVIATED, problems.joinToString("; "))
        }

        /** One line for saved metadata: how many frames applied the request. */
        fun summary(requested: ExposureSetting, checks: List<ManualFrameCheck>): String {
            val applied = checks.count { it.outcome == ManualOutcome.APPLIED }
            val firstProblem = checks.firstOrNull { it.outcome != ManualOutcome.APPLIED }
            return "requested ${ManualStops.shutterLabel(requested.exposureTimeNs)} ISO ${requested.iso}" +
                (if (requested.clamped) " (clamped to the sensor range)" else "") +
                "; applied on $applied of ${checks.size} frames" + (firstProblem?.let { ": ${it.outcome} ${it.detail}" } ?: "")
        }
    }
}
