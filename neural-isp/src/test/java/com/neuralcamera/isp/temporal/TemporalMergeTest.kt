package com.neuralcamera.isp.temporal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Validates the temporal merge against synthetic bursts with known ground truth. Passing here shows the algorithm does
 * what it claims on synthetic data; it is not Gate 3 evidence and says nothing about real captures.
 */
class TemporalMergeTest {

    private val w = 256
    private val h = 192
    private val scene = Scene(w, h)

    private fun frames(shifts: List<Pair<Double, Double>>, seed: Long = 1L, gains: List<Double>? = null,
                       overlay: ((Int, Int, Int) -> Double?)? = null, scn: Scene = scene): List<Frame> {
        val rng = Rng(seed)
        return shifts.mapIndexed { i, (tx, ty) ->
            val g = gains?.get(i) ?: 1.0
            Frame(Burst.render(scn, w, h, tx, ty, g, rng, overlay = overlay?.let { f -> { x: Int, y: Int -> f(i, x, y) } }), Burst.radiometry(g))
        }
    }

    private fun psnrOf(out: FloatPlane, truth: DoubleArray, region: ((Int, Int) -> Boolean)? = null) =
        Burst.psnr({ out.data[it].toDouble() }, truth, w, h, region)

    private fun psnrOfFrame(f: Frame, truth: DoubleArray, region: ((Int, Int) -> Boolean)? = null) =
        Burst.psnr({ f.radiometry.toCommon(f.plane.data[it].toInt() and 0xFFFF).toDouble() }, truth, w, h, region)

    private fun alignOf(ref: Frame, alt: Frame, params: AlignParams = AlignParams()): MotionField =
        TileAligner(params).align(AlignmentProxy.of(ref), AlignmentProxy.of(alt))

    private fun medianOf(values: List<Float>): Float = values.sorted()[values.size / 2]

    @Test
    fun alignmentRecoversKnownSubPixelTranslationsUnderNoise() {
        // 256x192 builds a 2-level pyramid, so the default radius (4 at the coarse level) reaches +-8 px; the last shift
        // needs a larger radius. Real 12 MP frames build 4 levels (+-32 px at the default radius).
        val shifts = listOf(0.0 to 0.0, 1.0 to 0.0, 2.5 to -1.25, -3.75 to 4.5, 7.3 to -6.6, -11.0 to 9.5)
        val burst = frames(shifts)
        val ref = burst[0]

        for (i in 1 until burst.size) {
            val field = alignOf(ref, burst[i], AlignParams(coarseRadius = if (i == burst.size - 1) 8 else 4))
            val interior = (0 until field.tilesY).flatMap { ky -> (0 until field.tilesX).map { kx -> field.index(kx, ky) } }
                .filter { val kx = it % field.tilesX; val ky = it / field.tilesX; kx in 3 until field.tilesX - 3 && ky in 3 until field.tilesY - 3 }
            val mx = medianOf(interior.map { field.dx[it] })
            val my = medianOf(interior.map { field.dy[it] })
            assertEquals("frame $i dx", shifts[i].first, mx.toDouble(), 0.15)
            assertEquals("frame $i dy", shifts[i].second, my.toDouble(), 0.15)
        }
    }

    @Test
    fun staticBurstGainsAboutTheExpectedNoiseReduction() {
        val burst = frames(List(8) { 0.0 to 0.0 })
        val truth = Burst.truth(scene, w, h)

        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        val single = psnrOfFrame(burst[0], truth)
        val merged = psnrOf(result.output, truth)
        assertTrue("merged $merged dB vs single $single dB (8 frames: ~9 dB expected, >= 7 required)", merged - single >= 7.0)
        assertTrue(result.frameStats.all { it.meanWeight > 0.6 })
    }

    @Test
    fun handShakeBurstBeatsBothTheReferenceAndBlindAveraging() {
        val rng = Rng(99)
        val shifts = List(8) { if (it == 0) 0.0 to 0.0 else rng.uniform(-3.0, 3.0) to rng.uniform(-3.0, 3.0) }
        val burst = frames(shifts)
        val truth = Burst.truth(scene, w, h)

        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        val single = psnrOfFrame(burst[0], truth)
        val merged = psnrOf(result.output, truth)
        val blind = Burst.naiveMean(burst.map { it.plane })
        val blindPsnr = Burst.psnr({ blind[it] }, truth, w, h)
        assertTrue("merged $merged vs single $single", merged - single >= 5.0)
        assertTrue("merged $merged vs blind average $blindPsnr (alignment must matter)", merged - blindPsnr >= 5.0)
    }

    @Test
    fun movingObjectDoesNotGhost() {
        // A bright block moves 7 px per frame across a static, hand-shake-free background.
        fun block(frame: Int, x: Int, y: Int): Double? {
            val bx = 80 + 7 * frame
            return if (x in bx until bx + 40 && y in 70 until 110) 0.85 else null
        }
        val burst = frames(List(8) { 0.0 to 0.0 }, overlay = ::block)
        val truth = Burst.truth(scene, w, h) { x, y -> block(0, x, y) } // the reference (frame 0) defines the target
        val trail: (Int, Int) -> Boolean = { x, y -> x in 70 until 140 && y in 60 until 120 }

        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        val mergedRegion = psnrOf(result.output, truth, trail)
        val blind = Burst.naiveMean(burst.map { it.plane })
        val blindRegion = Burst.psnr({ blind[it] }, truth, w, h, trail)
        val refRegion = psnrOfFrame(burst[0], truth, trail)
        assertTrue("object region: merged $mergedRegion dB vs blind $blindRegion dB", mergedRegion - blindRegion >= 8.0)
        assertTrue("object region: merged $mergedRegion dB must not be worse than the single reference $refRegion dB", mergedRegion >= refRegion - 1.0)
        // And the static part of the frame still denoises.
        val background: (Int, Int) -> Boolean = { x, y -> x > 170 || y > 140 }
        assertTrue(psnrOf(result.output, truth, background) - psnrOfFrame(burst[0], truth, background) >= 5.0)
    }

    @Test
    fun clippedHighlightIsRecoveredFromShorterExposures() {
        val bright = Scene(w, h, highlight = 2.0)
        // Reference: long exposure that clips the 2.0 highlight. Alternates: 1/4 exposure that records it.
        val gains = listOf(1.0, 0.25, 0.25, 0.25, 0.25)
        val burst = frames(List(5) { 0.0 to 0.0 }, gains = gains, scn = bright)
        val spot: (Int, Int) -> Boolean = { x, y -> Math.hypot(x - 0.75 * w, y - 0.25 * h) < 8.0 }

        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        var refMean = 0.0
        var mergedMean = 0.0
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (spot(x, y)) {
            refMean += burst[0].radiometry.toCommon(burst[0].plane.at(x, y))
            mergedMean += result.output.at(x, y)
            n++
        }
        refMean /= n
        mergedMean /= n
        assertTrue("reference spot reads ${refMean} (clipped near 1.0)", refMean < 1.05)
        assertEquals("merged spot should recover ~2.0", 2.0, mergedMean, 0.2)
    }

    @Test
    fun clippedAlternateSamplesCarryNoWeight() {
        // Reference is the short exposure and records the 0.6 highlight. The alternates have twice the gain and clip it
        // (1.2 > full scale), so at the spot only the reference may contribute.
        val bright = Scene(w, h, highlight = 0.6)
        val gains = listOf(1.0, 2.0, 2.0, 2.0)
        val burst = frames(List(4) { 0.0 to 0.0 }, gains = gains, scn = bright)
        val spot: (Int, Int) -> Boolean = { x, y -> Math.hypot(x - 0.75 * w, y - 0.25 * h) < 8.0 }
        assertTrue("alternate must really be clipped at the spot", burst[1].plane.at((0.75 * w).toInt(), (0.25 * h).toInt()) >= 4000)

        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        var mean = 0.0
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (spot(x, y)) { mean += result.output.at(x, y); n++ }
        mean /= n
        assertEquals("highlight must stay on the reference's scale", 0.6, mean, 0.04)
    }

    @Test
    fun resultDoesNotDependOnTheThreadCount() {
        val rng = Rng(21)
        val shifts = List(5) { if (it == 0) 0.0 to 0.0 else rng.uniform(-3.0, 3.0) to rng.uniform(-3.0, 3.0) }
        val burst = frames(shifts)

        val one = TemporalMerge.run(burst, 0, Burst.NOISE, threads = 1)
        val many = TemporalMerge.run(burst, 0, Burst.NOISE, threads = 4)

        org.junit.Assert.assertArrayEquals(one.output.data, many.output.data, 0f)
        assertEquals(one.frameStats, many.frameStats)
    }

    @Test
    fun singleFramePassesThroughOnTheCommonScale() {
        val burst = frames(listOf(0.0 to 0.0))
        val result = TemporalMerge.run(burst, referenceIndex = 0, noise = Burst.NOISE)

        for (i in listOf(1000, 5000, 20000, 40000)) {
            assertEquals(burst[0].radiometry.toCommon(burst[0].plane.data[i].toInt() and 0xFFFF), result.output.data[i], 1e-5f)
        }
        assertTrue(result.frameStats.isEmpty())
    }

    @Test
    fun framesThatCannotBeAlignedAreRejectedRatherThanBlended() {
        // The alternate has inverted contrast, so nothing in it matches: none of it may leak into the output.
        val rng = Rng(3)
        val ref = Frame(Burst.render(scene, w, h, 0.0, 0.0, 1.0, rng), Burst.radiometry())
        val alt = Frame(
            Burst.render(scene, w, h, 0.0, 0.0, 1.0, rng, overlay = { x, y -> 1.0 - scene.radiance(x.toDouble(), y.toDouble()) }),
            Burst.radiometry()
        )
        val truth = Burst.truth(scene, w, h)

        val result = TemporalMerge.run(listOf(ref, alt), referenceIndex = 0, noise = Burst.NOISE)

        val merged = psnrOf(result.output, truth)
        val refOnly = psnrOfFrame(ref, truth)
        assertTrue("merged $merged dB vs reference $refOnly dB: unrelated frame must not degrade the result", merged >= refOnly - 1.0)
        assertTrue("mean weight ${result.frameStats.single()}", result.frameStats.single().meanWeight < 0.25)
    }

    @Test
    fun invalidInputsAreRejected() {
        val burst = frames(listOf(0.0 to 0.0, 1.0 to 0.0))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TemporalMerge.run(burst, 5, Burst.NOISE) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TemporalMerge.run(emptyList(), 0, Burst.NOISE) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Radiometry(100.0, 50.0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NoiseModel(-1.0, 0.0) }
        assertTrue(abs(NoiseModel(0.002, 0.0001).variance(0.5) - 0.0011) < 1e-12)
    }
}
