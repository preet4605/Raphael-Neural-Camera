package com.neuralcamera.runtime.proof

/**
 * Deterministic synthetic test input: a smooth scene plus Gaussian noise. Bit-reproducible across JVM/ART because it
 * uses only integer PRNG steps and StrictMath transcendental functions.
 */
object DeterministicInput {

    /** Returns `height * width` FP32 values in [0, 1] (row-major). */
    fun noisyScene(height: Int, width: Int, seed: Long, noiseSigma: Double = 0.05): FloatArray {
        val rng = SplitMix64(seed)
        val cx = 0.3 + 0.4 * rng.nextUnit()
        val cy = 0.3 + 0.4 * rng.nextUnit()
        val radius = 0.12 + 0.1 * rng.nextUnit()
        val out = FloatArray(height * width)
        for (y in 0 until height) {
            val fy = y.toDouble() / height
            for (x in 0 until width) {
                val fx = x.toDouble() / height
                var v = 0.5 +
                    0.22 * StrictMath.sin(2 * StrictMath.PI * (1.5 * fx + 0.5 * fy)) +
                    0.12 * StrictMath.cos(2 * StrictMath.PI * 3 * fy)
                val dx = fx - cx
                val dy = fy - cy
                if (dx * dx + dy * dy < radius * radius) v += 0.2
                v += noiseSigma * rng.nextGaussian()
                out[y * width + x] = v.coerceIn(0.0, 1.0).toFloat()
            }
        }
        return out
    }
}

/** SplitMix64 PRNG; fixed integer arithmetic, so identical on every platform. */
internal class SplitMix64(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in (0, 1). */
    fun nextUnit(): Double = ((nextLong() ushr 11) + 0.5) / (1L shl 53).toDouble()

    fun nextGaussian(): Double {
        val u1 = nextUnit()
        val u2 = nextUnit()
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2 * StrictMath.PI * u2)
    }
}
