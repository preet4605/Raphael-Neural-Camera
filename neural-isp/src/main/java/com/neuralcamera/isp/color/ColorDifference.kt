package com.neuralcamera.isp.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Lab(val l: Double, val a: Double, val b: Double)

/** CIELAB relative to the pipeline's D50 white, and the CIEDE2000 colour difference (Sharma, Wu & Dalal 2005). */
object ColorDifference {
    private const val EPS = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    fun labFromXyz(xyz: DoubleArray, white: DoubleArray = OutputSpace.D50_XYZ): Lab {
        fun f(t: Double) = if (t > EPS) cbrt(t) else (KAPPA * t + 16) / 116
        val fx = f(xyz[0] / white[0]); val fy = f(xyz[1] / white[1]); val fz = f(xyz[2] / white[2])
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun xyzFromLab(lab: Lab, white: DoubleArray = OutputSpace.D50_XYZ): DoubleArray {
        val fy = (lab.l + 16) / 116
        val fx = fy + lab.a / 500
        val fz = fy - lab.b / 200
        fun inv(f: Double) = if (f * f * f > EPS) f * f * f else (116 * f - 16) / KAPPA
        val y = if (lab.l > KAPPA * EPS) fy * fy * fy else lab.l / KAPPA
        return doubleArrayOf(inv(fx) * white[0], y * white[1], inv(fz) * white[2])
    }

    fun deltaE2000(x: Lab, y: Lab): Double {
        val c1 = hypot(x.a, x.b); val c2 = hypot(y.a, y.b)
        val cBar7 = ((c1 + c2) / 2).pow(7)
        val g = 0.5 * (1 - sqrt(cBar7 / (cBar7 + 25.0.pow(7))))
        val a1 = (1 + g) * x.a; val a2 = (1 + g) * y.a
        val cp1 = hypot(a1, x.b); val cp2 = hypot(a2, y.b)
        fun hue(b: Double, a: Double): Double = if (b == 0.0 && a == 0.0) 0.0 else Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
        val h1 = hue(x.b, a1); val h2 = hue(y.b, a2)

        val dL = y.l - x.l
        val dC = cp2 - cp1
        val dh = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * sqrt(cp1 * cp2) * sin(Math.toRadians(dh / 2))

        val lBar = (x.l + y.l) / 2
        val cBarP = (cp1 + cp2) / 2
        val hBar = when {
            cp1 * cp2 == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hBar - 30)) + 0.24 * cos(Math.toRadians(2 * hBar)) +
            0.32 * cos(Math.toRadians(3 * hBar + 6)) - 0.20 * cos(Math.toRadians(4 * hBar - 63))
        val dTheta = 30 * exp(-((hBar - 275) / 25).pow(2))
        val cBarP7 = cBarP.pow(7)
        val rc = 2 * sqrt(cBarP7 / (cBarP7 + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lBar - 50).pow(2) / sqrt(20 + (lBar - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dL / sl).pow(2) + (dC / sc).pow(2) + (dH / sh).pow(2) + rt * (dC / sc) * (dH / sh))
    }
}

/** One colour-chart patch: where it is in the image and its reference colour (from the chart's own measurement data). */
data class ChartPatch(val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val reference: Lab)

data class PatchResult(val name: String, val measured: Lab, val reference: Lab, val deltaE2000: Double, val pixels: Int)

class ChartReport(val patches: List<PatchResult>, val exposureScale: Double, val note: String) {
    val meanDeltaE: Double get() = patches.map { it.deltaE2000 }.average()
    val maxDeltaE: Double get() = patches.maxOf { it.deltaE2000 }
}

/**
 * Measures chart patches in a linear render (before tone mapping): each patch's central half is averaged, converted to
 * XYZ D50 and Lab, and compared with its reference by CIEDE2000. With [normalizeTo] the measured luminance is scaled
 * so that patch's Y matches its reference, which removes the exposure difference (as chart ISO-style measurements do);
 * otherwise absolute values are compared.
 */
object ChartMeasurement {
    fun measure(linear: RgbImage, space: OutputSpace, patches: List<ChartPatch>, normalizeTo: String? = null): ChartReport {
        require(patches.isNotEmpty()) { "no patches" }
        val toXyz = space.toXyzD50
        val means = patches.associate { p ->
            val x0 = p.x + p.width / 4; val y0 = p.y + p.height / 4
            val x1 = p.x + p.width - p.width / 4; val y1 = p.y + p.height - p.height / 4
            require(x0 >= 0 && y0 >= 0 && x1 <= linear.width && y1 <= linear.height && x1 > x0 && y1 > y0) { "patch ${p.name} lies outside the image" }
            val sum = DoubleArray(3)
            for (y in y0 until y1) for (x in x0 until x1) for (c in 0 until 3) sum[c] += linear.data[(y * linear.width + x) * 3 + c]
            val n = (x1 - x0) * (y1 - y0)
            p.name to (toXyz.apply(DoubleArray(3) { sum[it] / n }) to n)
        }
        val scale = normalizeTo?.let { name ->
            val p = requireNotNull(patches.firstOrNull { it.name == name }) { "no patch named $name" }
            ColorDifference.xyzFromLab(p.reference)[1] / means.getValue(name).first[1]
        } ?: 1.0
        val results = patches.map { p ->
            val (xyz, n) = means.getValue(p.name)
            val lab = ColorDifference.labFromXyz(DoubleArray(3) { xyz[it] * scale })
            PatchResult(p.name, lab, p.reference, ColorDifference.deltaE2000(p.reference, lab), n)
        }
        val note = if (normalizeTo != null) "luminance normalized to patch '$normalizeTo' (scale %.4f)".format(java.util.Locale.ROOT, scale) else "absolute"
        return ChartReport(results, scale, note)
    }
}
