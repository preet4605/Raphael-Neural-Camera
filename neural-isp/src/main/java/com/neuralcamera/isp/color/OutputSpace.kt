package com.neuralcamera.isp.color

/**
 * Output RGB spaces. Both use the sRGB transfer curve, so tone mapping and 8-bit encoding are shared; only the
 * primaries differ. The XYZ (D50-adapted, Bradford) to linear RGB matrices are derived from the primaries and white;
 * the same derivation reproduces the published sRGB matrix (tested), which checks the Display P3 one.
 */
enum class OutputSpace(val label: String) {
    SRGB("sRGB"),
    DISPLAY_P3("Display P3");

    val fromXyzD50: Matrix3 by lazy {
        when (this) {
            SRGB -> xyzD50ToRgb(doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06), xyToXyz(D65_XY[0], D65_XY[1]))
            DISPLAY_P3 -> xyzD50ToRgb(doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060), xyToXyz(D65_XY[0], D65_XY[1]))
        }
    }

    /** Linear RGB to XYZ D50: columns are the D50-adapted primaries (the ICC rXYZ, gXYZ, bXYZ tags). */
    val toXyzD50: Matrix3 by lazy { fromXyzD50.inverse() }

    companion object {
        val D65_XY = doubleArrayOf(0.3127, 0.3290)
        /** The ICC PCS white (D50), which the XYZ->sRGB matrix in [ColorTransform] also assumes. */
        val D50_XYZ = doubleArrayOf(0.96422, 1.0, 0.82521)
        val BRADFORD = Matrix3(doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296))

        fun xyToXyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

        /** Chromatic adaptation from [src] white to [dst] white (XYZ, Y = 1). */
        fun bradford(src: DoubleArray, dst: DoubleArray): Matrix3 {
            val s = BRADFORD.apply(src)
            val d = BRADFORD.apply(dst)
            return BRADFORD.inverse() * Matrix3.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * BRADFORD
        }

        /** Standard RGB->XYZ from primaries xy (r, g, b) and the white's XYZ (Y = 1), adapted to D50, inverted. */
        fun xyzD50ToRgb(primaries: DoubleArray, white: DoubleArray): Matrix3 {
            val r = xyToXyz(primaries[0], primaries[1]); val g = xyToXyz(primaries[2], primaries[3]); val b = xyToXyz(primaries[4], primaries[5])
            val p = Matrix3(doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]))
            val s = p.inverse().apply(white)
            val rgbToXyz = p * Matrix3.diag(s[0], s[1], s[2])
            return (bradford(white, D50_XYZ) * rgbToXyz).inverse()
        }
    }
}
