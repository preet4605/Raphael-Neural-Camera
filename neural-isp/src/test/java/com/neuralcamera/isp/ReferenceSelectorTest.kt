package com.neuralcamera.isp

import com.neuralcamera.isp.temporal.U16Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReferenceSelectorTest {
    private val w = 64
    private val h = 64

    /** Checkerboard texture around [mean]; [blur] averages it away; [clipFraction] of rows saturate at 255. */
    private fun plane(mean: Int, contrast: Int, clipFraction: Double = 0.0, seed: Int = 1): U16Plane {
        val rnd = Random(seed)
        val data = ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = if (y < h * clipFraction) 255 else mean + (if (((x / 4) + (y / 4)) % 2 == 0) contrast else -contrast) + rnd.nextInt(-2, 3)
            v.coerceIn(0, 255).toShort()
        }
        return U16Plane(w, h, data)
    }

    @Test
    fun sharpestUsableFrameIsReference() {
        val choice = ReferenceSelector.select(listOf(plane(110, 10), plane(110, 40), plane(110, 20)))
        assertEquals(1, choice.index)
        assertFalse(choice.allFramesRejected)
    }

    @Test
    fun sharpButClippedFrameIsNeverReferenceWhenAUsableOneExists() {
        val clippedSharp = plane(110, 60, clipFraction = 0.5)
        val choice = ReferenceSelector.select(listOf(clippedSharp, plane(110, 20)))
        assertEquals(1, choice.index)
        assertTrue(choice.ranked.first { it.index == 0 }.rejected)
    }

    @Test
    fun allRejectedFallsBackToSharpestAndSaysSo() {
        val choice = ReferenceSelector.select(listOf(plane(110, 10, clipFraction = 0.5), plane(110, 40, clipFraction = 0.5)))
        assertEquals(1, choice.index)
        assertTrue(choice.allFramesRejected)
        assertTrue(choice.note.contains("every frame failed"))
    }
}
