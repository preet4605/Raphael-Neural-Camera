package com.neuralcamera.isp.temporal

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic-data validation of the RAW Bayer merge (known ground truth). Not evidence about real RAW frames. */
class BayerMergeTest {

    private val w = 256
    private val h = 192
    private val scene = Scene(w, h)
    private val noise = List(4) { Burst.NOISE }

    private fun burst(shifts: List<Pair<Double, Double>>, seed: Long = 5L): List<BayerFrame> {
        val rng = Rng(seed)
        return shifts.map { (tx, ty) -> BayerBurst.render(scene, w, h, tx, ty, 1.0, rng) }
    }

    private fun psnrPerChannel(values: (Int) -> Double, truth: DoubleArray): DoubleArray = DoubleArray(4) { p ->
        Burst.psnr(values, truth, w, h) { x, y -> (y % 2) * 2 + (x % 2) == p }
    }

    @Test
    fun handShakeBayerBurstDenoisesEveryColourChannelWithoutCrossTalk() {
        val rng = Rng(11)
        val shifts = List(8) { if (it == 0) 0.0 to 0.0 else rng.uniform(-3.0, 3.0) to rng.uniform(-3.0, 3.0) }
        val frames = burst(shifts)
        val truth = BayerBurst.truth(scene, w, h)

        val result = BayerTemporalMerge.run(frames, referenceIndex = 0, noise = noise)

        val single = BayerBurst.normalized(frames[0], w)
        val singlePsnr = psnrPerChannel({ single[it] }, truth)
        val mergedPsnr = psnrPerChannel({ result.mosaic.data[it].toDouble() }, truth)
        for (p in 0 until 4) {
            assertTrue("channel $p: single ${singlePsnr[p]} dB -> merged ${mergedPsnr[p]} dB", mergedPsnr[p] - singlePsnr[p] >= 5.0)
        }
        assertTrue(result.frameStats.size == 7 && result.frameStats.all { it.meanWeight > 0.5 })
    }

    @Test
    fun oddDimensionsAndBadArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BayerFrame(U16Plane(5, 4, ShortArray(20)), BayerBurst.radiometry())
        }
        assertThrows(IllegalArgumentException::class.java) { BayerRadiometry(doubleArrayOf(1.0), 4095.0) }
        val frames = burst(listOf(0.0 to 0.0, 1.0 to 0.0))
        assertThrows(IllegalArgumentException::class.java) { BayerTemporalMerge.run(frames, 0, listOf(Burst.NOISE)) }
        assertThrows(IllegalArgumentException::class.java) { BayerTemporalMerge.run(frames, 3, noise) }
    }
}
