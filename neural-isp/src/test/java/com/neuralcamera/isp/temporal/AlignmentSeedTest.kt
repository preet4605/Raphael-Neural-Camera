package com.neuralcamera.isp.temporal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeded alignment on synthetic bursts (test fixtures, not camera data). */
class AlignmentSeedTest {
    private val w = 256
    private val h = 192
    private val scene = Scene(w, h)

    private fun frame(tx: Double, ty: Double, seed: Long) = Frame(Burst.render(scene, w, h, tx, ty, 1.0, Rng(seed)), Burst.radiometry())

    /** Median motion over interior tiles that overlap the reference after a shift of up to ~40 px. */
    private fun medianMotion(field: MotionField): Pair<Float, Float> {
        val idx = (0 until field.tilesX * field.tilesY).filter { val kx = it % field.tilesX; val ky = it / field.tilesX; kx in 7 until field.tilesX - 7 && ky in 7 until field.tilesY - 7 }
        return idx.map { field.dx[it] }.sorted()[idx.size / 2] to idx.map { field.dy[it] }.sorted()[idx.size / 2]
    }

    @Test
    fun aSeedReachesAShiftBeyondTheSearchRange() {
        // 256x192 builds a 2-level pyramid: about +-12 px unseeded.
        val ref = AlignmentProxy.of(frame(0.0, 0.0, 1))
        val alt = AlignmentProxy.of(frame(30.4, -21.7, 2))
        val (ux, _) = medianMotion(TileAligner().align(ref, alt))
        assertTrue("unseeded alignment should not reach 30 px (got $ux)", kotlin.math.abs(ux - 30.4f) > 3f)
        val (sx, sy) = medianMotion(TileAligner().align(ref, alt, AlignmentSeed(28f, -19f)))
        assertEquals(30.4, sx.toDouble(), 0.2)
        assertEquals(-21.7, sy.toDouble(), 0.2)
    }

    @Test
    fun aWrongSeedDoesNotOverrideTheTrueSmallShift() {
        val ref = AlignmentProxy.of(frame(0.0, 0.0, 3))
        val alt = AlignmentProxy.of(frame(2.5, -1.25, 4))
        val (x, y) = medianMotion(TileAligner().align(ref, alt, AlignmentSeed(-40f, 35f)))
        assertEquals(2.5, x.toDouble(), 0.15)
        assertEquals(-1.25, y.toDouble(), 0.15)
    }

    @Test
    fun bayerMergeTakesSeedsInMosaicPixels() {
        val frames = listOf(0.0 to 0.0, 44.0 to 30.0).mapIndexed { i, (tx, ty) -> BayerBurst.render(scene, w, h, tx, ty, 1.0, Rng(10L + i)) }
        val noise = List(4) { Burst.NOISE }
        val unseeded = BayerTemporalMerge.run(frames, 0, noise, threads = 1).frameStats[0]
        val seeded = BayerTemporalMerge.run(frames, 0, noise, threads = 1, seeds = listOf(null, AlignmentSeed(42f, 31f))).frameStats[0]
        assertTrue("seeded ${seeded.meanWeight} vs unseeded ${unseeded.meanWeight}", seeded.meanWeight > unseeded.meanWeight + 0.2)
    }
}
