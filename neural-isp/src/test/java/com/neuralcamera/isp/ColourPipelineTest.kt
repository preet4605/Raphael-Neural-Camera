package com.neuralcamera.isp

import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.FramePlane
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.isp.temporal.Rng
import com.neuralcamera.isp.temporal.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colour comes only from real chroma planes; frames without them stay gray. Synthetic fixtures. */
class ColourPipelineTest {
    private val w = 64
    private val h = 48
    private val scene = Scene(w, h)

    private fun frame(index: Int, cb: Int, cr: Int, withChroma: Boolean, chromaRowStride: Int = w, rng: Rng = Rng(3)): CameraFrame {
        val y = ByteArray(w * h) { Math.round((scene.radiance((it % w).toDouble(), (it / w).toDouble()) + 0.01 * rng.gaussian()).coerceIn(0.0, 1.0) * 255).toByte() }
        // Camera2 style: U and V planes, half resolution, pixelStride 2 (interleaved), rowStride padded.
        fun chroma(value: Int) = ByteArray(chromaRowStride * (h / 2)) { if (it % 2 == 0) value.toByte() else 0x7F }
        val planes = if (withChroma) listOf(FramePlane(y, 1, w), FramePlane(chroma(cb), 2, chromaRowStride), FramePlane(chroma(cr), 2, chromaRowStride))
        else listOf(FramePlane(y, 1, w))
        return CameraFrame(
            "f$index", "YUV_420_888", w, h, planes,
            FrameMetadata(index.toLong(), index * 33_000_000L, 10_000_000L, 100, 5f, 2f, 1.8f, LensFacing.BACK_WIDE, "0", 90)
        )
    }

    private fun run(frames: List<CameraFrame>) = runBlocking { BaselineImagePipeline().processFrames(frames, w, h, false) }

    @Test
    fun redChromaGivesRedTintedColourOutput() {
        val r = run(List(3) { frame(it, cb = 90, cr = 200, withChroma = true, chromaRowStride = w + 8) })
        assertTrue(r.isColour)
        var red = 0L; var green = 0L; var blue = 0L
        for (i in 0 until w * h) {
            red += r.masterRgbPlane[i * 3].toInt() and 255
            green += r.masterRgbPlane[i * 3 + 1].toInt() and 255
            blue += r.masterRgbPlane[i * 3 + 2].toInt() and 255
        }
        assertTrue("R=$red G=$green B=$blue", red > green + w * h * 20 && red > blue + w * h * 20)
        assertTrue(r.chromaMerged == (r.realityGuardDecision.action != com.neuralcamera.quality.GuardAction.REVERT_TO_ORIGINAL))
        assertTrue(r.appliedPipelineName, r.appliedPipelineName.contains(if (r.chromaMerged) "chroma merged" else "chroma from the reference"))
    }

    @Test
    fun neutralChromaStaysNeutral() {
        val r = run(List(3) { frame(it, cb = 128, cr = 128, withChroma = true) })
        assertTrue(r.isColour)
        for (i in 0 until w * h) {
            assertEquals(r.masterRgbPlane[i * 3], r.masterRgbPlane[i * 3 + 1])
            assertEquals(r.masterRgbPlane[i * 3], r.masterRgbPlane[i * 3 + 2])
        }
    }

    @Test
    fun framesWithoutChromaPlanesStayGrayAndSaySo() {
        val r = run(List(3) { frame(it, 0, 0, withChroma = false) })
        assertFalse(r.isColour)
        assertFalse(r.chromaMerged)
        for (i in 0 until w * h) assertEquals(r.masterRgbPlane[i * 3], r.masterRgbPlane[i * 3 + 2])
    }

    @Test
    fun truncatedChromaBufferFallsBackToGrayInsteadOfReadingOutOfBounds() {
        val good = frame(0, 90, 200, true)
        val bad = good.copy(planes = listOf(good.planes[0], FramePlane(ByteArray(10), 2, w), good.planes[2]))
        run(listOf(bad, frame(1, 90, 200, true), frame(2, 90, 200, true), frame(3, 90, 200, true))) // must not throw
        assertEquals(null, ChromaExtractor.toFullRes(bad))
    }
}
