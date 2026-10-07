package com.neuralcamera.isp

import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.FramePlane
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.isp.temporal.Rng
import com.neuralcamera.isp.temporal.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pipeline-level checks on synthetic 8-bit luma bursts (test fixtures). */
class BaselinePipelineTemporalTest {

    private val w = 96
    private val h = 64
    private val scene = Scene(w, h)

    private fun frame(index: Int, tx: Double, ty: Double, rng: Rng, rowStride: Int = w, format: String = "YUV_420_888"): CameraFrame {
        val buffer = ByteArray(rowStride * h) { 0x5A } // padding bytes must never be read as pixels
        for (y in 0 until h) for (x in 0 until w) {
            val v = scene.radiance(x - tx, y - ty) + 0.02 * rng.gaussian()
            buffer[y * rowStride + x] = Math.round(v.coerceIn(0.0, 1.0) * 255).toByte()
        }
        return CameraFrame(
            frameId = "f$index", format = format, width = w, height = h,
            planes = listOf(FramePlane(buffer, 1, rowStride)),
            metadata = FrameMetadata(
                frameSequence = index.toLong(), timestampNs = index * 33_000_000L, exposureTimeNs = 10_000_000L, iso = 100,
                focalLengthMm = 5f, focusDistanceMeters = 2f, apertureFNumber = 1.8f, lensFacing = LensFacing.BACK_WIDE,
                physicalCameraId = "2", sensorOrientation = 90
            )
        )
    }

    @Test
    fun rowStrideIsHonouredAndPaddingNeverBecomesPixels() {
        val rng = Rng(1)
        val strided = frame(0, 0.0, 0.0, rng, rowStride = w + 16)

        val plane = LumaExtractor.toU16(strided)

        assertEquals(w, plane.width)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals((strided.planes[0].buffer[y * (w + 16) + x].toInt() and 0xFF), plane.at(x, y))
        }
    }

    @Test
    fun rawFramesAreRefusedRatherThanReadAsLuma() {
        val rng = Rng(2)
        assertThrows(IllegalArgumentException::class.java) { LumaExtractor.toU16(frame(0, 0.0, 0.0, rng, format = "RAW_SENSOR")) }
    }

    @Test
    fun truncatedOrInconsistentBuffersAreRejected() {
        val rng = Rng(3)
        val good = frame(0, 0.0, 0.0, rng)
        val shortBuffer = good.copy(planes = listOf(FramePlane(ByteArray(10), 1, w)))
        val tinyStride = good.copy(planes = listOf(FramePlane(good.planes[0].buffer, 1, w - 1)))
        assertThrows(IllegalArgumentException::class.java) { LumaExtractor.toU16(shortBuffer) }
        assertThrows(IllegalArgumentException::class.java) { LumaExtractor.toU16(tinyStride) }
        assertThrows(IllegalArgumentException::class.java) { LumaExtractor.toU16(good.copy(planes = emptyList())) }
    }

    @Test
    fun handShakeBurstIsAlignedAndMergedNotBlindlyAveraged() {
        val rng = Rng(4)
        val shifts = listOf(0.0 to 0.0, 1.5 to -1.0, -2.25 to 2.0, 3.0 to 1.25, -1.0 to -2.5, 2.0 to 3.0)
        val frames = shifts.mapIndexed { i, (tx, ty) -> frame(i, tx, ty, rng, rowStride = w + 8) }

        val result = runBlocking { BaselineImagePipeline().processFrames(frames, w, h, requestNeuralAcceleration = false) }

        assertEquals(w * h * 3, result.masterRgbPlane.size)
        assertEquals(w * h, result.originalLumaPlane.size)
        assertEquals(frames.size - 1, result.temporalStats.size)
        assertTrue("every alternate frame should contribute: ${result.temporalStats}", result.temporalStats.all { it.meanWeight > 0.4 })
        assertTrue(result.appliedPipelineName.contains("motion-robust"))
        assertTrue(!result.isNeuralAccelerated)
    }
}
