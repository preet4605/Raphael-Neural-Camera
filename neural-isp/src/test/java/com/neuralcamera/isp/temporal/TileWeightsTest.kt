package com.neuralcamera.isp.temporal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-tile merge confidence on synthetic bursts (test fixtures, not camera data). */
class TileWeightsTest {
    private val w = 256
    private val h = 192
    private val scene = Scene(w, h)

    @Test
    fun aMovingObjectLowersOnlyTheTilesItCovers() {
        // A bright square at x 96..143, y 64..111 in frame 1 only: frame 1 disagrees there and nowhere else.
        val inBox = { x: Int, y: Int -> x in 96..143 && y in 64..111 }
        val frames = (0..1).map { i ->
            Frame(Burst.render(scene, w, h, 0.0, 0.0, 1.0, Rng(20L + i), overlay = if (i == 1) { x, y -> if (inBox(x, y)) 0.85 else null } else null), Burst.radiometry())
        }
        val tw = TemporalMerge.run(frames, 0, Burst.NOISE, threads = 2).frameStats[0].tileWeights!!
        // Tile k covers pixels origin(k) .. origin(k) + 15.
        fun covered(kx: Int, ky: Int) = (0 until TileGrid.TILE).all { d -> inBox(TileGrid.origin(kx) + d, TileGrid.origin(ky) + d) }
        fun clear(kx: Int, ky: Int) = (0 until TileGrid.TILE).none { du -> (0 until TileGrid.TILE).any { dv -> inBox(TileGrid.origin(kx) + du, TileGrid.origin(ky) + dv) } }
        val inside = (0 until tw.tilesY).flatMap { ky -> (0 until tw.tilesX).filter { covered(it, ky) }.map { tw.at(it, ky) } }
        val outside = (2 until tw.tilesY - 2).flatMap { ky -> (2 until tw.tilesX - 2).filter { clear(it, ky) }.map { tw.at(it, ky) } }
        assertTrue(inside.isNotEmpty() && outside.isNotEmpty())
        assertTrue("inside max ${inside.max()}", inside.max() < 0.1f)
        assertTrue("outside median ${outside.sorted()[outside.size / 2]}", outside.sorted()[outside.size / 2] > 0.8f)
        assertTrue(tw.fractionBelow(0.5f) in 0.03..0.25)
    }

    @Test
    fun bayerWeightsAverageTheFourPlanes() {
        val frames = (0..1).map { BayerBurst.render(scene, w, h, 0.0, 0.0, 1.0, Rng(30L + it)) }
        val tw = BayerTemporalMerge.run(frames, 0, List(4) { Burst.NOISE }, threads = 1).frameStats[0].tileWeights!!
        assertEquals(TileGrid.tilesFor(w / 2), tw.tilesX)
        assertTrue(tw.fractionBelow(0.5f) < 0.02)
        val m = TileWeights.meanOf(listOf(TileWeights(1, 1, floatArrayOf(Float.NaN)), TileWeights(1, 1, floatArrayOf(0.4f))))
        assertEquals(0.4f, m.at(0, 0), 0f)
    }
}
