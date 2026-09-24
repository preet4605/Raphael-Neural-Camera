package com.neuralcamera.video

import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.FramePlane
import com.neuralcamera.deviceprofiles.LensFacing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineTest {

    private fun createFrame(seq: Long): CameraFrame {
        return CameraFrame(
            frameId = "v_$seq",
            format = "YUV_420_888",
            width = 64,
            height = 64,
            planes = listOf(FramePlane(ByteArray(64 * 64), 1, 64)),
            metadata = FrameMetadata(
                frameSequence = seq,
                timestampNs = seq * 33_333_333L,
                exposureTimeNs = 16_000_000L,
                iso = 100,
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
    fun testVideoPipelineEnforcesSelectiveKeyframeProcessing() {
        runBlocking {
            val pipeline = StandardVideoPipeline()
            pipeline.prepare(VideoRecordingConfig())

            var enhancedCount = 0
            for (i in 1L..30L) {
                val res = pipeline.processFrame(createFrame(i))
                assertFalse(res.dropped)
                if (res.neuralEnhanced) {
                    enhancedCount++
                }
            }

            // Rule 24: Frame 15 and 30 are enhanced (2 out of 30 frames), not all 30!
            assertEquals(2, enhancedCount)
            pipeline.finalizeRecording()
        }
    }
}
