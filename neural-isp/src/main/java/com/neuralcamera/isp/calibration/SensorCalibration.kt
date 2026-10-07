package com.neuralcamera.isp.calibration

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.NoiseModel

/*
 * Sensor calibration contracts. Every value carries where it came from; a value the camera did not report is absent,
 * never invented. Per-device numbers for the OnePlus 15 have not been collected: NOT_TESTED.
 */

enum class CalibrationSource {
    /** Per-frame CaptureResult (e.g. SENSOR_DYNAMIC_BLACK_LEVEL, SENSOR_NOISE_PROFILE, lens shading map). */
    CAPTURE_RESULT,

    /** Static CameraCharacteristics (e.g. SENSOR_BLACK_LEVEL_PATTERN, SENSOR_INFO_WHITE_LEVEL, colour matrices). */
    CHARACTERISTICS,

    /** Measured by this project with a documented procedure (dark frames, flat fields) for one camera/lens. */
    LAB_MEASURED,

    /** Read from a DNG's tags. */
    DNG_TAGS
}

data class Sourced<T>(val value: T, val source: CalibrationSource)

/** Identity of the calibrated optical path. Calibration never transfers across physical cameras or lenses. */
data class CameraKey(val logicalCameraId: String, val physicalCameraId: String, val sensorModel: String?) {
    override fun toString() = "$logicalCameraId/$physicalCameraId${sensorModel?.let { "/$it" } ?: ""}"
}

/**
 * Lens shading gain map as Camera2 delivers it (STATISTICS_LENS_SHADING_CORRECTION_MAP): a [rows] x [columns] grid of
 * gains >= 1 for the four channels in the order R, G_even, G_odd, B, where G_even is the green on even mosaic rows.
 */
class LensShadingMap(val columns: Int, val rows: Int, val gains: FloatArray) {
    init {
        require(columns >= 2 && rows >= 2) { "the shading grid needs at least 2x2 points" }
        require(gains.size == columns * rows * 4) { "expected ${columns * rows * 4} gains, got ${gains.size}" }
        require(gains.all { it.isFinite() && it >= 1f - 1e-3f }) { "shading gains must be finite and >= 1" }
    }

    fun gain(channel: Int, column: Int, row: Int): Float = gains[(row * columns + column) * 4 + channel]

    /** Bilinear gain for [channel] at normalized position (u, v) in [0,1]^2 over the full mosaic. */
    fun interpolate(channel: Int, u: Double, v: Double): Float {
        val fx = u.coerceIn(0.0, 1.0) * (columns - 1)
        val fy = v.coerceIn(0.0, 1.0) * (rows - 1)
        val x0 = fx.toInt().coerceAtMost(columns - 2); val y0 = fy.toInt().coerceAtMost(rows - 2)
        val ax = fx - x0; val ay = fy - y0
        val top = gain(channel, x0, y0) * (1 - ax) + gain(channel, x0 + 1, y0) * ax
        val bottom = gain(channel, x0, y0 + 1) * (1 - ax) + gain(channel, x0 + 1, y0 + 1) * ax
        return (top * (1 - ay) + bottom * ay).toFloat()
    }

    companion object {
        /** Shading-map channel (0 R, 1 G_even, 2 G_odd, 3 B) of a CFA position (row parity * 2 + column parity). */
        fun channelOf(cfa: CfaPattern, position: Int): Int = when (cfa.colors[position]) {
            0 -> 0
            2 -> 3
            else -> if (position / 2 == 0) 1 else 2
        }
    }
}

/** A hot/dead/stuck pixel from a calibration table, in mosaic coordinates. */
data class DefectPixel(val x: Int, val y: Int)

/**
 * Gain-dependent noise model in normalized units (variance = S * x + O, x = black-subtracted signal / range), the form
 * of Camera2 SENSOR_NOISE_PROFILE and the DNG NoiseProfile tag. S grows linearly with gain; O quadratically
 * (read noise before gain) plus a constant (after gain).
 */
data class GainNoiseModel(val s1: Double, val s0: Double, val o2: Double, val o0: Double) {
    fun at(iso: Int): NoiseModel {
        val g = iso / 100.0
        return NoiseModel(shot = (s1 * g + s0).coerceAtLeast(0.0), read = (o2 * g * g + o0).coerceAtLeast(0.0))
    }

    companion object {
        data class Sample(val iso: Int, val shot: Double, val read: Double)

        /**
         * Least-squares fit from per-ISO (S, O) measurements (e.g. SENSOR_NOISE_PROFILE at several ISOs, or a lab fit).
         * Needs at least two distinct ISOs.
         */
        fun fit(samples: List<Sample>): GainNoiseModel {
            require(samples.map { it.iso }.distinct().size >= 2) { "need samples at two or more ISO values" }
            val (s1, s0) = linearFit(samples.map { it.iso / 100.0 }, samples.map { it.shot })
            val (o2, o0) = linearFit(samples.map { (it.iso / 100.0).let { g -> g * g } }, samples.map { it.read })
            return GainNoiseModel(s1, s0, o2, o0)
        }

        private fun linearFit(x: List<Double>, y: List<Double>): Pair<Double, Double> {
            val n = x.size
            val mx = x.average(); val my = y.average()
            var sxy = 0.0; var sxx = 0.0
            for (i in 0 until n) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx) }
            val slope = if (sxx == 0.0) 0.0 else sxy / sxx
            return slope to (my - slope * mx)
        }
    }
}

/** Two-illuminant colour calibration (DNG ColorMatrix1/2, ForwardMatrix1/2), row-major 3x3, as reported. */
data class ColorCalibration(
    val colorMatrix1: DoubleArray?,
    val colorMatrix2: DoubleArray?,
    val forwardMatrix1: DoubleArray?,
    val forwardMatrix2: DoubleArray?,
    /** EXIF LightSource codes of the two calibration illuminants (e.g. 17 = StdA, 21 = D65). */
    val illuminant1: Int?,
    val illuminant2: Int?
) {
    init {
        listOf(colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2).forEach {
            require(it == null || it.size == 9) { "colour matrices are 3x3" }
        }
    }

    val hasAnyMatrix: Boolean get() = listOfNotNull(colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2).isNotEmpty()

    override fun equals(other: Any?) = other is ColorCalibration &&
        colorMatrix1.contentEquals(other.colorMatrix1) && colorMatrix2.contentEquals(other.colorMatrix2) &&
        forwardMatrix1.contentEquals(other.forwardMatrix1) && forwardMatrix2.contentEquals(other.forwardMatrix2) &&
        illuminant1 == other.illuminant1 && illuminant2 == other.illuminant2

    override fun hashCode() = listOf(colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2)
        .fold(31 * (illuminant1 ?: 0) + (illuminant2 ?: 0)) { h, m -> 31 * h + (m?.contentHashCode() ?: 0) }
}

/**
 * Temperature-dependent calibration hook. Sensor black level and dark current drift with temperature. Implementations
 * adjust a calibration for a measured sensor/device temperature. The default does nothing and says so; no
 * temperature model has been measured.
 */
fun interface TemperatureCompensation {
    /** Returns the adjusted calibration and a note, or the input unchanged with a note explaining why. */
    fun adjust(calibration: SensorCalibration, temperatureC: Double?): Pair<SensorCalibration, String>

    companion object {
        val NONE = TemperatureCompensation { c, t -> c to "no temperature model (temperature=${t ?: "unknown"}); calibration unchanged" }
    }
}

/** Everything the RAW front end needs for one frame of one camera. */
data class SensorCalibration(
    val camera: CameraKey,
    val cfa: Sourced<CfaPattern>,
    /** Per CFA position (row parity * 2 + column parity), in DN. */
    val blackLevels: Sourced<DoubleArray>,
    val whiteLevel: Sourced<Double>,
    val defects: List<DefectPixel> = emptyList(),
    val lensShading: Sourced<LensShadingMap>? = null,
    /** Per CFA position noise at the frame's ISO; null when the camera did not report it. */
    val noise: Sourced<List<NoiseModel>>? = null,
    val color: Sourced<ColorCalibration>? = null
) {
    init {
        require(blackLevels.value.size == 4) { "four CFA black levels are required" }
        require(blackLevels.value.all { it < whiteLevel.value }) { "black level must be below white level" }
        require(noise == null || noise.value.size == 4) { "one noise model per CFA position" }
    }

    /** Names of the calibration inputs that are missing; the RAW pipeline reports these as degraded processing. */
    fun missing(): List<String> = buildList {
        if (lensShading == null) add("lens shading map")
        if (noise == null) add("noise profile")
        if (color == null || !color.value.hasAnyMatrix) add("colour matrices")
    }
}
