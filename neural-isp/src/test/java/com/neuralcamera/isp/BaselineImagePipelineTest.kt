package com.neuralcamera.isp

import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.FramePlane
import com.neuralcamera.deviceprofiles.LensFacing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineImagePipelineTest {

    private fun createFrame(seq: Long, noiseOffset: Int): CameraFrame {
        val width = 32
        val height = 32
        val buffer = ByteArray(width * height) { ((100 + noiseOffset) % 256).toByte() }
        return CameraFrame(
            frameId = "f_$seq",
            format = "YUV_420_888",
            width = width,
            height = height,
            planes = listOf(FramePlane(buffer, 1, width)),
            metadata = FrameMetadata(
                frameSequence = seq,
                timestampNs = seq * 33_000_000L,
                exposureTimeNs = 16_000_000L,
                iso = 400,
                focalLengthMm = 5.0f,
                focusDistanceMeters = 2.0f,
                apertureFNumber = 1.8f,
                lensFacing = LensFacing.BACK_WIDE,
                physicalCameraId = "0",
                sensorOrientation = 0
            )
        )
    }

    @Test
    fun testProcessFramesExecutesAndProducesMaster() {
        runBlocking {
            val pipeline = BaselineImagePipeline()
            val frames = listOf(
                createFrame(1, -5),
                createFrame(2, 0),
                createFrame(3, +5)
            )

            val result = pipeline.processFrames(
                frames = frames,
                targetWidth = 32,
                targetHeight = 32,
                requestNeuralAcceleration = false
            )

            assertEquals(32, result.outputWidth)
            assertEquals(32, result.outputHeight)
            assertEquals(32 * 32 * 3, result.masterRgbPlane.size)
            assertFalse(result.isNeuralAccelerated)
            assertNotNull(result.realityGuardDecision)
            assertTrue(result.qualityScore.isAcceptable)
            assertTrue(result.metrics.isSuccess)
        }
    }
}
