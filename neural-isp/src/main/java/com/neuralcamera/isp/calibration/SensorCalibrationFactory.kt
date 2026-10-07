package com.neuralcamera.isp.calibration

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.NoiseModel

/**
 * Camera2 calibration values as plain data, so the conversion below is testable without Android. Null means the
 * camera did not report the key. Filled on the device by `isp.camera2.Camera2CalibrationReader`.
 */
class Camera2CalibrationInput(
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR, 4 RGB, 5 MONO, 6 NIR). */
    val colorFilterArrangement: Int?,
    /** SENSOR_BLACK_LEVEL_PATTERN per CFA position (row parity * 2 + column parity), in DN. */
    val blackLevelPattern: IntArray?,
    /** SENSOR_INFO_WHITE_LEVEL. */
    val whiteLevel: Int?,
    /** CaptureResult SENSOR_DYNAMIC_BLACK_LEVEL, same layout as the pattern. */
    val dynamicBlackLevel: FloatArray? = null,
    /** CaptureResult SENSOR_DYNAMIC_WHITE_LEVEL. */
    val dynamicWhiteLevel: Int? = null,
    /** CaptureResult SENSOR_NOISE_PROFILE as (S, O) pairs, one per CFA channel in CFA position order. */
    val noiseProfile: List<Pair<Double, Double>>? = null,
    /** CaptureResult STATISTICS_LENS_SHADING_CORRECTION_MAP (present only when the request enabled the map). */
    val lensShading: LensShadingMap? = null,
    /** SENSOR_COLOR_TRANSFORM1/2 (DNG ColorMatrix1/2) and SENSOR_FORWARD_MATRIX1/2, row-major 3x3. */
    val colorTransform1: DoubleArray? = null,
    val colorTransform2: DoubleArray? = null,
    val forwardMatrix1: DoubleArray? = null,
    val forwardMatrix2: DoubleArray? = null,
    /** SENSOR_REFERENCE_ILLUMINANT1/2 (EXIF LightSource codes). */
    val referenceIlluminant1: Int? = null,
    val referenceIlluminant2: Int? = null
)

/** A calibration, or null with the reason; [notes] say which source each value came from and what was ignored. */
class CalibrationBuild(val calibration: SensorCalibration?, val notes: List<String>)

/**
 * Builds a [SensorCalibration] from what Camera2 reported. Nothing is invented: CFA, black level and white level are
 * required and a missing one yields no calibration; optional inputs that are missing or malformed are left null so
 * [SensorCalibration.missing] reports them. Per-frame result values win over static characteristics.
 */
object SensorCalibrationFactory {

    fun fromCamera2(camera: CameraKey, input: Camera2CalibrationInput): CalibrationBuild {
        val notes = ArrayList<String>()
        fun unavailable(reason: String) = CalibrationBuild(null, notes + reason)

        val cfa = when (input.colorFilterArrangement) {
            0 -> CfaPattern.RGGB
            1 -> CfaPattern.GRBG
            2 -> CfaPattern.GBRG
            3 -> CfaPattern.BGGR
            null -> return unavailable("colour filter arrangement not reported")
            else -> return unavailable("colour filter arrangement ${input.colorFilterArrangement} is not a 2x2 Bayer mosaic")
        }

        val dynamicBlack = input.dynamicBlackLevel?.takeIf { it.size == 4 && it.all { v -> v.isFinite() && v >= 0f } }
        if (input.dynamicBlackLevel != null && dynamicBlack == null) notes += "dynamic black level malformed; ignored"
        val staticBlack = input.blackLevelPattern?.takeIf { it.size == 4 && it.all { v -> v >= 0 } }
        if (input.blackLevelPattern != null && staticBlack == null) notes += "black level pattern malformed; ignored"
        val black = when {
            dynamicBlack != null -> Sourced(DoubleArray(4) { dynamicBlack[it].toDouble() }, CalibrationSource.CAPTURE_RESULT)
            staticBlack != null -> Sourced(DoubleArray(4) { staticBlack[it].toDouble() }, CalibrationSource.CHARACTERISTICS)
            else -> return unavailable("no black level reported")
        }
        notes += "black level from ${black.source}"

        val white = when {
            input.dynamicWhiteLevel != null && input.dynamicWhiteLevel > 0 -> Sourced(input.dynamicWhiteLevel.toDouble(), CalibrationSource.CAPTURE_RESULT)
            input.whiteLevel != null && input.whiteLevel > 0 -> Sourced(input.whiteLevel.toDouble(), CalibrationSource.CHARACTERISTICS)
            else -> return unavailable("no white level reported")
        }
        notes += "white level from ${white.source}"
        if (black.value.any { it >= white.value }) return unavailable("black level ${black.value.toList()} is not below white level ${white.value}")

        val noise = input.noiseProfile?.let { noiseByPosition(it, cfa, notes) }?.let { Sourced(it, CalibrationSource.CAPTURE_RESULT) }
        if (input.noiseProfile == null) notes += "no noise profile in the capture result"

        val shading = input.lensShading?.let { Sourced(it, CalibrationSource.CAPTURE_RESULT) }
        if (shading == null) notes += "no lens shading map in the capture result (STATISTICS_LENS_SHADING_MAP_MODE off?)"

        val matrices = listOf(input.colorTransform1, input.colorTransform2, input.forwardMatrix1, input.forwardMatrix2)
            .map { m -> m?.takeIf { it.size == 9 && it.all(Double::isFinite) } }
        if (matrices.zip(listOf(input.colorTransform1, input.colorTransform2, input.forwardMatrix1, input.forwardMatrix2)).any { (ok, raw) -> raw != null && ok == null }) {
            notes += "a colour matrix was malformed; ignored"
        }
        val colour = ColorCalibration(matrices[0], matrices[1], matrices[2], matrices[3], input.referenceIlluminant1, input.referenceIlluminant2)
            .takeIf { it.hasAnyMatrix }?.let { Sourced(it, CalibrationSource.CHARACTERISTICS) }

        return CalibrationBuild(SensorCalibration(camera, Sourced(cfa, CalibrationSource.CHARACTERISTICS), black, white,
            lensShading = shading, noise = noise, color = colour), notes)
    }

    /** One pair per CFA position; three pairs (R, G, B) or one shared pair are expanded. Anything else is ignored. */
    private fun noiseByPosition(pairs: List<Pair<Double, Double>>, cfa: CfaPattern, notes: MutableList<String>): List<NoiseModel>? {
        if (pairs.any { (s, o) -> !s.isFinite() || !o.isFinite() || s < 0 || o < 0 }) {
            notes += "noise profile has negative or non-finite coefficients; ignored"
            return null
        }
        fun model(i: Int) = NoiseModel(pairs[i].first, pairs[i].second)
        return when (pairs.size) {
            4 -> List(4) { model(it) }
            3 -> List(4) { model(cfa.colors[it]) }
            1 -> List(4) { model(0) }
            else -> null.also { notes += "noise profile has ${pairs.size} pairs; expected 1, 3 or 4; ignored" }
        }
    }
}
