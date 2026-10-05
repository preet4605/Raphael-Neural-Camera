package com.neuralcamera.isp.temporal

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic synthetic scenes and bursts for validating the temporal merge. These are TEST FIXTURES: they exercise
 * the algorithm against known ground truth and say nothing about real camera data.
 */
internal class Rng(seed: Long) {
    private var s = seed
    fun nextLong(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun unit(): Double = ((nextLong() ushr 11) + 0.5) / (1L shl 53).toDouble()
    fun uniform(lo: Double, hi: Double) = lo + (hi - lo) * unit()
    fun gaussian(): Double = sqrt(-2.0 * StrictMath.log(unit())) * StrictMath.cos(2 * PI * unit())
}

/** Continuous analytic scene (so sub-pixel shifts are exact): gradient, band-limited texture, soft-edged shapes. */
internal class Scene(private val w: Int, private val h: Int, seed: Long = 7L, private val highlight: Double? = null) {
    private class Comp(val amp: Double, val fx: Double, val fy: Double, val phase: Double)

    private val comps: List<Comp>

    init {
        val rng = Rng(seed)
        comps = List(14) { Comp(rng.uniform(0.015, 0.05), rng.uniform(-0.15, 0.15), rng.uniform(-0.15, 0.15), rng.uniform(0.0, 2 * PI)) }
    }

    private fun smooth(edgeDistance: Double): Double {
        val t = ((edgeDistance + 0.75) / 1.5).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun radiance(x: Double, y: Double): Double {
        var v = 0.35 + 0.10 * (x / w) + 0.08 * (y / h)
        for (c in comps) v += c.amp * sin(2 * PI * (c.fx * x + c.fy * y) + c.phase)
        v += 0.25 * smooth(18.0 - hypot(x - 0.3 * w, y - 0.35 * h))                              // disc
        v -= 0.20 * smooth(minOf(minOf(x - 0.55 * w, 0.8 * w - x), minOf(y - 0.55 * h, 0.85 * h - y)))   // dark box
        v = v.coerceIn(0.03, 0.9)
        if (highlight != null) v += (highlight - v) * smooth(14.0 - hypot(x - 0.75 * w, y - 0.25 * h))  // bright spot
        return v
    }
}

internal object Burst {
    const val BLACK = 64.0
    const val WHITE = 4095.0
    val NOISE = NoiseModel(shot = 0.002, read = 0.0001)

    fun radiometry(gain: Double = 1.0) = Radiometry(BLACK, WHITE, gain)

    /** content moved by (tx, ty): frame(x, y) = scene(x - tx, y - ty); optional overlay replaces pixels inside [object]. */
    fun render(
        scene: Scene, w: Int, h: Int, tx: Double, ty: Double, gain: Double, rng: Rng,
        noise: NoiseModel = NOISE, overlay: ((Int, Int) -> Double?)? = null
    ): U16Plane {
        val dn = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val base = overlay?.invoke(x, y) ?: scene.radiance(x - tx, y - ty)
            val signal = base * gain
            val noisy = signal + rng.gaussian() * sqrt(noise.shot * maxOf(signal, 0.0) + noise.read)
            dn[y * w + x] = Math.round(BLACK + noisy * (WHITE - BLACK)).toInt().coerceIn(0, WHITE.toInt())
        }
        return U16Plane.fromInts(w, h, dn)
    }

    fun truth(scene: Scene, w: Int, h: Int, overlay: ((Int, Int) -> Double?)? = null) =
        DoubleArray(w * h) { overlay?.invoke(it % w, it / w) ?: scene.radiance((it % w).toDouble(), (it / w).toDouble()) }

    /** PSNR (peak 1.0) over the interior (16 px margin), optionally restricted to [region]. */
    fun psnr(values: (Int) -> Double, truth: DoubleArray, w: Int, h: Int, region: ((Int, Int) -> Boolean)? = null): Double {
        var se = 0.0
        var n = 0
        for (y in 16 until h - 16) for (x in 16 until w - 16) {
            if (region != null && !region(x, y)) continue
            val d = values(y * w + x) - truth[y * w + x]
            se += d * d
            n++
        }
        val mse = se / n
        return if (mse == 0.0) 200.0 else 10 * log10(1.0 / mse)
    }

    fun naiveMean(frames: List<U16Plane>, radiometry: Radiometry = radiometry()): DoubleArray {
        val n = frames.first().data.size
        val out = DoubleArray(n)
        for (f in frames) for (i in 0 until n) out[i] += radiometry.toCommon(f.data[i].toInt() and 0xFFFF).toDouble() / frames.size
        return out
    }
}

/** Synthetic RGGB mosaics: each CFA site samples the scene at its own full-resolution position, scaled per channel. */
internal object BayerBurst {
    val BLACKS = doubleArrayOf(64.0, 65.0, 65.0, 66.0)
    val CHANNEL_SCALE = doubleArrayOf(1.0, 0.8, 0.8, 0.6) // R, Gr, Gb, B
    const val WHITE = 4095.0

    fun radiometry(gain: Double = 1.0) = BayerRadiometry(BLACKS, WHITE, gain)

    fun render(scene: Scene, w: Int, h: Int, tx: Double, ty: Double, gain: Double, rng: Rng): BayerFrame {
        val dn = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = (y % 2) * 2 + (x % 2)
            val signal = scene.radiance(x - tx, y - ty) * CHANNEL_SCALE[p] * gain
            val noisy = signal + rng.gaussian() * sqrt(Burst.NOISE.shot * maxOf(signal, 0.0) + Burst.NOISE.read)
            dn[y * w + x] = Math.round(BLACKS[p] + noisy * (WHITE - BLACKS[p])).toInt().coerceIn(0, WHITE.toInt())
        }
        return BayerFrame(U16Plane.fromInts(w, h, dn), radiometry(gain))
    }

    fun truth(scene: Scene, w: Int, h: Int) =
        DoubleArray(w * h) { scene.radiance((it % w).toDouble(), (it / w).toDouble()) * CHANNEL_SCALE[((it / w) % 2) * 2 + (it % w) % 2] }

    /** The frame's own values on the common scale, in mosaic layout. */
    fun normalized(frame: BayerFrame, w: Int): DoubleArray = DoubleArray(frame.mosaic.data.size) {
        val p = ((it / w) % 2) * 2 + (it % w) % 2
        frame.radiometry.forPosition(p).toCommon(frame.mosaic.data[it].toInt() and 0xFFFF).toDouble()
    }
}
