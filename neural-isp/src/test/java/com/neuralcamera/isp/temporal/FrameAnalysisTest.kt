package com.neuralcamera.isp.temporal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalysisTest {

    private fun noisyPlane(w: Int, h: Int, sigma: Double, seed: Long, texture: Boolean): FloatPlane {
        val rng = Rng(seed)
        val scene = Scene(w, h)
        return FloatPlane(w, h, FloatArray(w * h) {
            val base = if (texture) scene.radiance((it % w).toDouble(), (it / w).toDouble()) else 0.5
            (base + sigma * rng.gaussian()).toFloat()
        })
    }

    @Test
    fun noiseSigmaMatchesTheTrueSigmaOnFlatAndTexturedScenes() {
        for (sigma in listOf(0.005, 0.02, 0.05)) {
            assertEquals("flat sigma $sigma", sigma, FrameAnalysis.noiseSigma(noisyPlane(256, 192, sigma, 1, texture = false)), sigma * 0.08)
            assertEquals("textured sigma $sigma", sigma, FrameAnalysis.noiseSigma(noisyPlane(256, 192, sigma, 2, texture = true)), sigma * 0.25) // structure biases it upward
        }
    }

    @Test
    fun noiseSigmaIsZeroForNoiseFreeFlatInputAndIgnoresNaN() {
        assertEquals(0.0, FrameAnalysis.noiseSigma(FloatPlane(32, 32, FloatArray(32 * 32) { 0.4f })), 1e-9)
        val p = noisyPlane(64, 64, 0.02, 3, texture = false)
        for (i in 0 until 200) p.data[i * 7] = Float.NaN
        assertEquals(0.02, FrameAnalysis.noiseSigma(p), 0.004)
        assertEquals(0.0, FrameAnalysis.noiseSigma(FloatPlane(2, 2, FloatArray(4))), 0.0)
    }

    @Test
    fun sharpestFramePrefersTheLessBlurredOne() {
        val w = 128
        val h = 96
        val scene = Scene(w, h)
        val rng = Rng(9)
        val sharp = Burst.render(scene, w, h, 0.0, 0.0, 1.0, rng, noise = NoiseModel(0.0, 1e-8))
        // Box-blur 5x5 of the same frame.
        val blurData = IntArray(w * h) { i ->
            var s = 0
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (i % w) + dx
                val y = (i / w) + dy
                if (x in 0 until w && y in 0 until h) { s += sharp.at(x, y); n++ }
            }
            s / n
        }
        val blurred = U16Plane.fromInts(w, h, blurData)

        assertEquals(0, FrameAnalysis.sharpestIndex(listOf(sharp, blurred)))
        assertEquals(1, FrameAnalysis.sharpestIndex(listOf(blurred, sharp)))
        assertTrue(FrameAnalysis.laplacianVariance(sharp) > FrameAnalysis.laplacianVariance(blurred))
    }

    @Test
    fun aBlurredButVeryNoisyFrameDoesNotLookSharperThanACleanSharpOne() {
        val w = 128
        val h = 96
        val scene = Scene(w, h)
        val clean = Burst.render(scene, w, h, 0.0, 0.0, 1.0, Rng(1), noise = NoiseModel(0.0, 1e-6))
        val noisy = Burst.render(scene, w, h, 0.0, 0.0, 1.0, Rng(2), noise = NoiseModel(0.0, 4e-3)) // sigma ~ 0.063
        val blurred = U16Plane.fromInts(w, h, IntArray(w * h) { i ->
            var sum = 0
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (i % w) + dx
                val y = (i / w) + dy
                if (x in 0 until w && y in 0 until h) { sum += noisy.at(x, y); n++ }
            }
            sum / n + (Rng(i.toLong()).gaussian() * 40).toInt() // blur, then heavy noise again
        })

        assertEquals(0, FrameAnalysis.sharpestIndex(listOf(clean, blurred)))
        assertEquals(1, FrameAnalysis.sharpestIndex(listOf(blurred, clean)))
    }
}
