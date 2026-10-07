package com.neuralcamera.gallery.provenance

import com.neuralcamera.models.execution.ContentOrigin
import com.neuralcamera.models.execution.FallbackAction
import com.neuralcamera.models.execution.PipelinePath
import com.neuralcamera.models.execution.StageOutcome
import com.neuralcamera.models.execution.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {
    private fun rec(path: PipelinePath, vararg stages: StageRecord) =
        ProvenanceRecord("m1", path, "YUV_420_888", 4, stages.toList(), false, "BALANCED", null)

    @Test
    fun normalCaptureIsReconstructedAndDeclaresDegradation() {
        val merge = ProvenanceRecord.recordOf(
            StageOutcome.Degraded("TEMPORAL_MERGE", Unit, "htp", "cpu", "no verified HTP"), ContentOrigin.RECONSTRUCTED
        )
        val p = rec(PipelinePath.COMPUTATIONAL_PHOTOGRAPHY, merge)
        assertEquals(ContentOrigin.RECONSTRUCTED, p.outputOrigin)
        assertTrue(p.degraded)
        val json = p.toJson()
        assertTrue(json.contains("\"status\":\"DEGRADED\"") && json.contains("\"implementation\":\"cpu\"") && json.contains("\"intended\":\"htp\""))
        assertTrue(p.exifSummary().contains("origin=RECONSTRUCTED") && p.exifSummary().contains("degraded"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generatedContentOnACameraPathIsRejected() {
        rec(PipelinePath.COMPUTATIONAL_PHOTOGRAPHY, StageRecord("fill", StageStatus.SUCCESS, "gen", "gen", null, ContentOrigin.GENERATED))
    }

    @Test
    fun aiStudioMayGenerateAndSaysSo() {
        val p = rec(PipelinePath.AI_STUDIO, StageRecord("fill", StageStatus.SUCCESS, "gen", "gen", null, ContentOrigin.GENERATED))
        assertEquals(ContentOrigin.GENERATED, p.outputOrigin)
    }

    @Test
    fun failedReconstructionLeavesCapturedOrigin() {
        val failed = ProvenanceRecord.recordOf(StageOutcome.Failed("TEMPORAL_MERGE", "too few frames", FallbackAction.USE_SINGLE_FRAME), ContentOrigin.RECONSTRUCTED)
        val p = rec(PipelinePath.CAPTURE, failed)
        assertEquals(ContentOrigin.CAPTURED, p.outputOrigin)
        assertTrue(p.toJson().contains("USE_SINGLE_FRAME"))
    }

    @Test
    fun jsonEscapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ProvenanceRecord.q("a\"b\\c\n"))
        val ok = rec(PipelinePath.CAPTURE, StageRecord("s", StageStatus.SUCCESS, "x", "x", "line\"1", ContentOrigin.CAPTURED))
        assertFalse(ok.degraded)
        assertTrue(ok.toJson().contains("line\\\"1"))
    }
}
