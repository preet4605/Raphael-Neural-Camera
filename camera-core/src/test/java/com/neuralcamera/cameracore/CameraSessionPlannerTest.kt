package com.neuralcamera.cameracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionPlannerTest {

    @Test
    fun testSizeSelectors() {
        val sizes = listOf(
            CameraStreamSize(4000, 3000), // 12 MP
            CameraStreamSize(1920, 1080), // 1080p 16:9
            CameraStreamSize(1440, 1080), // 1080p 4:3
            CameraStreamSize(1280, 720),  // 720p 16:9
            CameraStreamSize(640, 480)    // VGA 4:3
        )

        // Still selector should select max resolution
        val maxStill = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        assertEquals(CameraStreamSize(4000, 3000), maxStill)

        // Preview selector for 16:9 target ratio
        val previewCandidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val targetRatio = 16f / 9f
        val bestPreview = previewCandidates.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            Math.abs(ratio - targetRatio)
        }
        assertEquals(CameraStreamSize(1920, 1080), bestPreview)

        // Analysis selector bounded to <= 1280
        val analysisCandidates = sizes.filter { it.width <= 1280 }
        val bestAnalysis = analysisCandidates.maxByOrNull { it.width * it.height }
        assertEquals(CameraStreamSize(1280, 720), bestAnalysis)
    }

    @Test
    fun testPlannedSessionDataContract() {
        val stream = PlannedStream(
            role = "PREVIEW",
            format = 0x22, // PRIVATE
            size = CameraStreamSize(1920, 1080),
            streamUseCase = 1L
        )

        val session = PlannedSession(
            targetUseCase = SessionTargetUseCase.PREVIEW_ONLY,
            streams = listOf(stream),
            isSupportedByHardware = true,
            validationReason = "Validated"
        )

        assertTrue(session.isSupportedByHardware)
        assertEquals(1, session.streams.size)
        assertEquals("PREVIEW", session.streams.first().role)
        assertEquals("1920x1080", session.streams.first().size.toString())
    }
}
