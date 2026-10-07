package com.neuralcamera.isp.color

import kotlin.math.abs
import kotlin.math.sqrt

/** Correlated colour temperature from CIE 1931 xy by Robertson's method (the isotemperature-line table, 1968). */
object Cct {
    // mired, u, v, slope of the isotemperature line (CIE 1960 uv).
    private val TABLE = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341), doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876), doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470), doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156), doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955), doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888), doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471), doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182), doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512), doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637), doubleArrayOf(325.0, 0.24702, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641), doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633), doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262), doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324), doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325), doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45)
    )

    fun fromXy(x: Double, y: Double): Double {
        val den = -2 * x + 12 * y + 3
        val u = 4 * x / den
        val v = 6 * y / den
        var lastDt = 0.0
        for (i in 1 until TABLE.size) {
            val ui = TABLE[i][1]
            val vi = TABLE[i][2]
            val ti = TABLE[i][3]
            val len = sqrt(1 + ti * ti)
            var dt = -(u - ui) * (ti / len) + (v - vi) * (1 / len)
            if (dt <= 0 || i == TABLE.size - 1) {
                if (dt > 0) dt = 0.0
                dt = -dt
                val f = if (i == 1) 0.0 else dt / (lastDt + dt)
                return 1e6 / (TABLE[i - 1][0] * f + TABLE[i][0] * (1 - f))
            }
            lastDt = dt
        }
        error("unreachable")
    }

    fun xyOf(xyz: DoubleArray): Pair<Double, Double> {
        val s = xyz[0] + xyz[1] + xyz[2]
        return (xyz[0] / s) to (xyz[1] / s)
    }
}

/** CCT of the EXIF LightSource codes that name a CIE standard illuminant; other codes have no single defined CCT. */
object CalibrationIlluminants {
    fun kelvin(exifLightSource: Int?): Double? = when (exifLightSource) {
        17 -> 2856.0 // Standard light A
        18 -> 4874.0 // Standard light B
        19 -> 6774.0 // Standard light C
        20 -> 5503.0 // D55
        21 -> 6504.0 // D65
        22 -> 7504.0 // D75
        23 -> 5003.0 // D50
        else -> null
    }
}

/**
 * Dual-illuminant interpolation as the DNG specification describes it: find the scene white's xy by iterating
 * xy -> CCT -> interpolated ColorMatrix -> inv(CM) * neutral -> xy, with matrices interpolated linearly in inverse CCT
 * between the two calibration illuminants (clamped outside them). ForwardMatrices use the same weight.
 */
object DualIlluminant {

    class Result(
        val colorMatrix: DoubleArray?,
        val forwardMatrix: DoubleArray?,
        /** Scene white CCT, when it could be estimated. */
        val cctKelvin: Double?,
        /** Weight of calibration set 1 (1 = set 1 only). */
        val weight1: Double,
        val note: String
    )

    private const val MAX_ITERATIONS = 30

    fun interpolate(
        neutral: DoubleArray?, cm1: DoubleArray?, cm2: DoubleArray?, fm1: DoubleArray?, fm2: DoubleArray?,
        illuminant1: Int?, illuminant2: Int?
    ): Result {
        val t1 = CalibrationIlluminants.kelvin(illuminant1)
        val t2 = CalibrationIlluminants.kelvin(illuminant2)
        val second = cm2 != null || fm2 != null
        fun single(note: String) = Result(cm1, fm1, null, 1.0, note)
        if (!second) return single("single calibration illuminant")
        if (t1 == null || t2 == null || t1 == t2) return single("illuminants $illuminant1/$illuminant2 have no distinct standard CCT; set 1 used")
        if (cm1 == null || cm2 == null) return single("both ColorMatrices are needed to estimate the scene white; set 1 used")
        if (neutral == null || neutral.size != 3 || neutral.any { !it.isFinite() || it <= 0 }) return single("no as-shot neutral; set 1 used")

        fun weight(t: Double): Double {
            val (lo, hi, loIsSet1) = if (t1 < t2) Triple(t1, t2, true) else Triple(t2, t1, false)
            val gLo = when {
                t <= lo -> 1.0
                t >= hi -> 0.0
                else -> (1 / t - 1 / hi) / (1 / lo - 1 / hi)
            }
            return if (loIsSet1) gLo else 1 - gLo
        }
        fun mix(a: DoubleArray, b: DoubleArray, g: Double) = DoubleArray(9) { g * a[it] + (1 - g) * b[it] }

        var x = 0.3457; var y = 0.3585 // start at D50
        var cct = Cct.fromXy(x, y)
        var g = weight(cct)
        for (iteration in 0 until MAX_ITERATIONS) {
            val white = Matrix3(mix(cm1, cm2, g)).inverse().apply(neutral)
            if (white.any { !it.isFinite() } || white.sum() <= 0) return single("ColorMatrix inversion failed; set 1 used")
            val (nx, ny) = Cct.xyOf(white)
            val converged = abs(nx - x) + abs(ny - y) < 1e-7
            // Damp the last iterations so an oscillation settles between its two points.
            if (iteration >= MAX_ITERATIONS - 5) { x = (x + nx) / 2; y = (y + ny) / 2 } else { x = nx; y = ny }
            cct = Cct.fromXy(x, y)
            g = weight(cct)
            if (converged) break
        }
        // A lone ForwardMatrix is only valid for its own illuminant; without both, the ColorMatrix path is used.
        val fm = if (fm1 != null && fm2 != null) mix(fm1, fm2, g) else null
        return Result(mix(cm1, cm2, g), fm, cct, g, "dual-illuminant: scene %.0f K, weight %.3f on illuminant %d".format(java.util.Locale.ROOT, cct, g, illuminant1))
    }
}
