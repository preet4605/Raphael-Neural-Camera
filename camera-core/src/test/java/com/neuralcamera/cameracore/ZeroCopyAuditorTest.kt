package com.neuralcamera.cameracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroCopyAuditorTest {

    @Test
    fun testZeroCopyAuditWithHardwareBuffer() {
        val report = ZeroCopyAuditor.auditPipeline(
            previewWidth = 1920,
            previewHeight = 1080,
            stillWidth = 4000,
            stillHeight = 3000,
            useHardwareBuffer = true
        )

        assertEquals("NO_COPIES_EXPECTED", report.zeroCopyDesignStatus)
        assertEquals("NOT_MEASURED", report.actualHardwarePathStatus)
        assertTrue(report.copies.none { it.measured })
        assertEquals(0L, report.totalPerFrameCopiedBytes)
        assertTrue(report.copies.isNotEmpty())
    }

    @Test
    fun testAuditWithJvmPlaneFallbackReportsPartial() {
        val report = ZeroCopyAuditor.auditPipeline(
            previewWidth = 1920,
            previewHeight = 1080,
            stillWidth = 4000,
            stillHeight = 3000,
            useHardwareBuffer = false
        )

        assertEquals("COPIES_EXPECTED", report.zeroCopyDesignStatus)
        assertEquals("NOT_MEASURED", report.actualHardwarePathStatus)
        assertTrue("Per-frame copy bytes should be non-zero when copying to JVM byte arrays", report.totalPerFrameCopiedBytes > 0)
    }
}
