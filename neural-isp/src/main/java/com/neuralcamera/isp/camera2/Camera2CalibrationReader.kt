package com.neuralcamera.isp.camera2

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import com.neuralcamera.isp.calibration.Camera2CalibrationInput
import com.neuralcamera.isp.calibration.LensShadingMap

/**
 * Reads the Camera2 keys [com.neuralcamera.isp.calibration.SensorCalibrationFactory] needs. A pure key-to-data copy;
 * all interpretation lives in the factory. Values from a real OnePlus 15 have not been read yet: NOT_TESTED.
 */
object Camera2CalibrationReader {

    fun read(chars: CameraCharacteristics, result: CaptureResult?): Camera2CalibrationInput {
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return Camera2CalibrationInput(
            colorFilterArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            // getOffsetForIndex takes (column, row); positions are row parity * 2 + column parity.
            blackLevelPattern = pattern?.let { p -> IntArray(4) { p.getOffsetForIndex(it % 2, it / 2) } },
            whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            dynamicBlackLevel = result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
            dynamicWhiteLevel = result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL),
            noiseProfile = result?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.map { it.first to it.second },
            lensShading = result?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { m ->
                val gains = FloatArray(m.columnCount * m.rowCount * 4)
                for (row in 0 until m.rowCount) for (col in 0 until m.columnCount) for (ch in 0 until 4) {
                    // Channel order R, G_even, G_odd, B is RggbChannelVector's, the same as LensShadingMap's.
                    gains[(row * m.columnCount + col) * 4 + ch] = m.getGainFactor(ch, col, row)
                }
                runCatching { LensShadingMap(m.columnCount, m.rowCount, gains) }.getOrNull()
            },
            colorTransform1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let(::rowMajor),
            colorTransform2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let(::rowMajor),
            forwardMatrix1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::rowMajor),
            forwardMatrix2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let(::rowMajor),
            referenceIlluminant1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            referenceIlluminant2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        )
    }

    /** getElement takes (column, row). */
    private fun rowMajor(t: ColorSpaceTransform) = DoubleArray(9) { t.getElement(it % 3, it / 3).toDouble() }
}
