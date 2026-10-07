package com.neuralcamera.isp.color

/**
 * Camera-native linear RGB to linear sRGB, built from DNG calibration the way the DNG specification describes it.
 *
 * Two sources are supported:
 *  - ForwardMatrix1 (camera white-balanced to XYZ D50): `XYZ_D50 = FM * diag(1/neutral) * cam`. Preferred.
 *  - ColorMatrix1 (XYZ to camera, for the calibration illuminant): the scene white in XYZ is `inv(CM) * neutral`; the
 *    camera is mapped to XYZ with `inv(CM)` and Bradford-adapted from that white to D50.
 * Both end with the standard D50-adapted XYZ to linear sRGB matrix. Without calibration data the transform is a plain
 * white-balance only (identity matrix); callers should treat that as unverified colour.
 */
class ColorTransform(
    /** Camera linear RGB (black-subtracted, normalized) to linear [output] RGB, white balance included. */
    val cameraToLinearOutput: Matrix3,
    val source: Source,
    /** How the calibration was chosen (dual-illuminant interpolation or why not). */
    val note: String = "",
    val output: OutputSpace = OutputSpace.SRGB
) {
    enum class Source { FORWARD_MATRIX, COLOR_MATRIX, WHITE_BALANCE_ONLY }

    fun apply(r: Float, g: Float, b: Float, out: FloatArray, offset: Int) {
        val m = cameraToLinearOutput.m
        out[offset] = (m[0] * r + m[1] * g + m[2] * b).toFloat()
        out[offset + 1] = (m[3] * r + m[4] * g + m[5] * b).toFloat()
        out[offset + 2] = (m[6] * r + m[7] * g + m[8] * b).toFloat()
    }

    companion object {
        private val XYZ_D50_TO_SRGB = Matrix3(doubleArrayOf(
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427
        ))
        private val BRADFORD = Matrix3(doubleArrayOf(
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296
        ))
        // The white the XYZ_D50_TO_SRGB matrix assumes (Lindbloom); a rounded value leaves a cast on white.
        private val D50_WHITE = doubleArrayOf(0.96422, 1.0, 0.82521)

        private fun bradford(srcWhite: DoubleArray, dstWhite: DoubleArray): Matrix3 {
            val s = BRADFORD.apply(srcWhite)
            val d = BRADFORD.apply(dstWhite)
            return BRADFORD.inverse() * Matrix3.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * BRADFORD
        }

        /**
         * @param asShotNeutral camera-space coordinates of the scene neutral (DNG AsShotNeutral), normalized as stored
         * @param forwardMatrix 9 values row-major, or null
         * @param colorMatrix 9 values row-major, or null
         */
        fun from(asShotNeutral: DoubleArray?, forwardMatrix: DoubleArray?, colorMatrix: DoubleArray?, output: OutputSpace = OutputSpace.SRGB): ColorTransform {
            // sRGB keeps the published matrix; other spaces use the matrix derived from their primaries.
            val toOutput = if (output == OutputSpace.SRGB) XYZ_D50_TO_SRGB else output.fromXyzD50
            val neutral = asShotNeutral?.takeIf { it.size == 3 && it.all { v -> v.isFinite() && v > 0 } }
            val wb = neutral?.let { Matrix3.diag(1.0 / it[0], 1.0 / it[1], 1.0 / it[2]) } ?: Matrix3.IDENTITY
            val fm = forwardMatrix?.takeIf { it.size == 9 && it.all(Double::isFinite) }?.let { Matrix3(it) }
            if (fm != null && neutral != null) {
                return ColorTransform(toOutput * fm * wb, Source.FORWARD_MATRIX, output = output)
            }
            val cm = colorMatrix?.takeIf { it.size == 9 && it.all(Double::isFinite) }?.let { Matrix3(it) }
            if (cm != null && neutral != null) {
                val camToXyz = cm.inverse()
                val white = camToXyz.apply(neutral)
                return ColorTransform(toOutput * bradford(white, D50_WHITE) * camToXyz, Source.COLOR_MATRIX, output = output)
            }
            // No calibration: white balance only, in camera space whatever the requested output.
            return ColorTransform(wb, Source.WHITE_BALANCE_ONLY, output = output)
        }

        /** As [from], after interpolating two calibration sets to the scene's estimated CCT ([DualIlluminant]). */
        fun fromCalibration(
            asShotNeutral: DoubleArray?, colorMatrix1: DoubleArray?, colorMatrix2: DoubleArray?,
            forwardMatrix1: DoubleArray?, forwardMatrix2: DoubleArray?, illuminant1: Int?, illuminant2: Int?,
            output: OutputSpace = OutputSpace.SRGB
        ): ColorTransform {
            val valid = { m: DoubleArray? -> m?.takeIf { it.size == 9 && it.all(Double::isFinite) } }
            val r = DualIlluminant.interpolate(asShotNeutral, valid(colorMatrix1), valid(colorMatrix2), valid(forwardMatrix1),
                valid(forwardMatrix2), illuminant1, illuminant2)
            val t = from(asShotNeutral, r.forwardMatrix ?: valid(forwardMatrix1).takeIf { r.weight1 == 1.0 }, r.colorMatrix, output)
            return ColorTransform(t.cameraToLinearOutput, t.source, r.note, output)
        }
    }
}
