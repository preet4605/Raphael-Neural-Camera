package com.neuralcamera.isp

import com.neuralcamera.isp.temporal.MotionField
import com.neuralcamera.isp.temporal.Rng
import com.neuralcamera.isp.temporal.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Chroma merge along luma motion on synthetic planes (test fixtures, not camera data). */
class ChromaMergeTest {
    private val cw = 96
    private val ch = 64

    private fun zeroField(w: Int, h: Int): MotionField {
        val n = TileGrid.tilesFor(w) * TileGrid.tilesFor(h)
        return MotionField(TileGrid.tilesFor(w), TileGrid.tilesFor(h), FloatArray(n), FloatArray(n), FloatArray(n))
    }

    private fun noisy(cb: Int, cr: Int, seed: Long, patch: ((Int, Int) -> Pair<Int, Int>?)? = null): ChromaExtractor.HalfChroma {
        val rng = Rng(seed)
        fun v(base: Int) = Math.round(base + 6 * rng.gaussian()).toInt().coerceIn(0, 255)
        val b = IntArray(cw * ch); val r = IntArray(cw * ch)
        for (i in b.indices) {
            val p = patch?.invoke(i % cw, i / cw)
            b[i] = v(p?.first ?: cb); r[i] = v(p?.second ?: cr)
        }
        return ChromaExtractor.HalfChroma(cw, ch, b, r)
    }

    private fun sigma(values: IntArray, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        val s = (y0 until y1).flatMap { y -> (x0 until x1).map { x -> values[y * cw + x].toDouble() } }
        val m = s.average()
        return sqrt(s.sumOf { (it - m) * (it - m) } / s.size)
    }

    @Test
    fun chromaTilesTakeTheHalvedMotionOfTheLumaTileWithTheSameCentre() {
        val lw = 2 * cw; val lh = 2 * ch
        val luma = zeroField(lw, lh)
        for (i in luma.dx.indices) { luma.dx[i] = (i % luma.tilesX).toFloat(); luma.dy[i] = -(i / luma.tilesX).toFloat() }
        val c = ChromaMerge.chromaField(luma, cw, ch)
        assertEquals(TileGrid.tilesFor(cw), c.tilesX)
        assertEquals(3f, c.dx[c.index(3, 2)], 0f)   // luma tile x = 6, halved
        assertEquals(-2f, c.dy[c.index(3, 2)], 0f)  // luma tile y = 4, halved
        assertEquals((luma.tilesX - 1) / 2f, c.dx[c.index(c.tilesX - 1, 0)], 0f) // clamped at the edge
    }

    @Test
    fun mergedChromaIsLessNoisyAndAMovedColouredPatchDoesNotBleed() {
        val inPatch = { x: Int, y: Int -> x in 40..55 && y in 24..39 }
        val frames = (0 until 6).map { i -> noisy(110, 150, 40L + i, if (i == 3) { x, y -> if (inPatch(x, y)) 200 to 60 else null } else null) }
        val fields = List(5) { zeroField(2 * cw, 2 * ch) }
        val out = ChromaMerge.merge(frames, 0, fields, threads = 1).chroma
        val before = sigma(frames[0].cb, 8, 32, 8, 56)
        val after = sigma(out.cb, 8, 32, 8, 56)
        assertTrue("sigma $before -> $after", after < 0.6 * before)
        // Inside the patch the merged chroma stays at the scene value (110, 150), not pulled toward (200, 60).
        val patchMeanCb = (24..39).flatMap { y -> (40..55).map { x -> out.cb[y * cw + x] } }.average()
        val patchMeanCr = (24..39).flatMap { y -> (40..55).map { x -> out.cr[y * cw + x] } }.average()
        assertEquals(110.0, patchMeanCb, 3.0)
        assertEquals(150.0, patchMeanCr, 3.0)
    }
}
