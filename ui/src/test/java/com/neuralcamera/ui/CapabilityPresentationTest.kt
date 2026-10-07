package com.neuralcamera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityPresentationTest {

    @Test
    fun zoomShortcutsComeOnlyFromTheReportedRange() {
        assertEquals(listOf(1.0f), CapabilityPresentation.zoomButtons(null))
        assertEquals(listOf(1.0f, 2.0f, 3.0f), CapabilityPresentation.zoomButtons(1.0f..4.0f))
        assertEquals(CapabilityPresentation.CANDIDATE_ZOOMS, CapabilityPresentation.zoomButtons(0.6f..10.0f))
        // A range that starts at 0.5999 (float rounding of 0.6) still offers .6.
        assertEquals(0.6f, CapabilityPresentation.zoomButtons(0.5999f..2.0f).first())
    }

    @Test
    fun labelsAndBadgeSayOnlyWhatHappened() {
        assertEquals(".6", CapabilityPresentation.zoomLabel(0.6f))
        assertEquals("3x", CapabilityPresentation.zoomLabel(3.0f))
        assertEquals("Zoom 2 times, selected", CapabilityPresentation.zoomDescription(2.0f, true))
        assertEquals("CLASSICAL", CapabilityPresentation.processingBadge(false, "QNN HTP"))
        assertEquals("NEURAL · ONNX CPU", CapabilityPresentation.processingBadge(true, "ONNX CPU"))
    }
}
