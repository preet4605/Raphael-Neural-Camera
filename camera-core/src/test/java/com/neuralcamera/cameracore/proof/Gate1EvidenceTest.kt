package com.neuralcamera.cameracore.proof

import com.neuralcamera.benchmarks.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logic tests for the Gate 1 evaluator. The records are test fixtures, not captured frames. */
class Gate1EvidenceTest {

    private fun frame(i: Int) = RawFrameRecord(
        requestIndex = i, frameNumber = 100L + i, sensorTimestampNs = 1_000_000_000L + i * 150_000_000L,
        imageTimestampNs = 1_000_000_000L + i * 150_000_000L, imageArrivalElapsedNs = 1_500_000_000L + i * 150_000_000L,
        resultArrivalElapsedNs = 1_400_000_000L + i * 150_000_000L, width = 8192, height = 6144, format = 0x20,
        exposureTimeNs = 10_000_000L, iso = 100, rowStride = 16384, pixelStride = 2, planeBytes = 16384L * 6144,
        pixelSha256 = "hash$i", dngFile = "frame_$i.dng", dngBytes = 8192L * 6144 * 2 + 4096
    )

    private fun evidence(frames: List<RawFrameRecord> = (0 until 8).map { frame(it) }) = Gate1RunEvidence(
        runId = "r", processStartElapsedRealtimeMs = 1L, device = mapOf("model" to "x"),
        app = mapOf("isSystemApp" to false, "uid" to 10234), camera = emptyMap(), requestedFrames = frames.size.coerceAtLeast(8),
        width = 8192, height = 6144, sensorPixelMode = "MAXIMUM_RESOLUTION", timestampSourceRealtime = true,
        frames = frames, events = emptyList(), completedNormally = true, failure = null
    )

    private fun failed(e: Gate1RunEvidence) = Gate1.evaluate(e).filter { !it.passed }.map { it.name }.toSet()

    @Test
    fun cleanEightFrameBurstPassesEveryCriterion() {
        assertEquals(emptySet<String>(), failed(evidence()))
    }

    @Test
    fun eachDefectIsCaught() {
        val good = (0 until 8).map { frame(it) }
        fun with(i: Int, f: (RawFrameRecord) -> RawFrameRecord) = good.mapIndexed { idx, r -> if (idx == i) f(r) else r }

        assertTrue("every_frame_full_resolution" in failed(evidence(with(3) { it.copy(width = 4096, height = 3072) })))
        assertTrue("every_frame_raw_sensor_format" in failed(evidence(with(3) { it.copy(format = 0x100) })))
        assertTrue("zero_drops" in failed(evidence(with(2) { it.copy(dropped = true, droppedReason = "buffer lost") })))
        assertTrue("frame_numbers_consecutive" in failed(evidence(with(4) { it.copy(frameNumber = 999L) })))
        assertTrue("sensor_timestamps_strictly_monotonic" in failed(evidence(with(5) { it.copy(sensorTimestampNs = 1L, imageTimestampNs = 1L) })))
        assertTrue("sensor_timestamps_strictly_monotonic" in failed(evidence(with(5) { it.copy(sensorTimestampNs = good[4].sensorTimestampNs, imageTimestampNs = good[4].sensorTimestampNs) })))
        assertTrue("image_timestamp_equals_result_timestamp" in failed(evidence(with(1) { it.copy(imageTimestampNs = 5L) })))
        assertTrue("metadata_valid" in failed(evidence(with(6) { it.copy(exposureTimeNs = 0) })))
        assertTrue("metadata_valid" in failed(evidence(with(6) { it.copy(iso = null) })))
        assertTrue("plane_covers_full_frame" in failed(evidence(with(0) { it.copy(planeBytes = 1000) })))
        assertTrue("no_duplicate_frames" in failed(evidence(with(7) { it.copy(pixelSha256 = good[0].pixelSha256) })))
        assertTrue("no_duplicate_frames" in failed(evidence(with(7) { it.copy(pixelSha256 = null) })))
        assertTrue("arrival_not_before_capture" in failed(evidence(with(2) { it.copy(imageArrivalElapsedNs = 1L) })))
        assertTrue("dng_written_for_every_frame" in failed(evidence(with(2) { it.copy(dngFile = null) })))
        assertTrue("dng_written_for_every_frame" in failed(evidence(with(2) { it.copy(dngBytes = 1000) })))
    }

    @Test
    fun fewerThanEightFramesOrMissingRecordsFail() {
        val seven = (0 until 7).map { frame(it) }
        val e = evidence(seven).copy(requestedFrames = 7)
        assertTrue("requested_at_least_8_frames" in failed(e))
        val missing = evidence().copy(frames = (0 until 8).filter { it != 3 }.map { frame(it) })
        assertTrue("all_requested_frames_present" in failed(missing))
    }

    @Test
    fun systemAppOrFailedRunOrLostBufferEventFails() {
        assertTrue("ordinary_non_system_app" in failed(evidence().copy(app = mapOf("isSystemApp" to true, "uid" to 1000))))
        assertTrue("ordinary_non_system_app" in failed(evidence().copy(app = emptyMap())))
        assertTrue("run_completed_normally" in failed(evidence().copy(completedNormally = false, failure = "crash")))
        assertTrue("zero_drops" in failed(evidence().copy(events = listOf("LOST buffer frame=105"))))
    }

    @Test
    fun arrivalCheckIsSkippedWhenTimestampSourceIsNotRealtime() {
        val e = evidence((0 until 8).map { frame(it).copy(imageArrivalElapsedNs = 1L) }).copy(timestampSourceRealtime = false)
        assertFalse("arrival_not_before_capture" in failed(e))
    }

    @Test
    fun jsonAndCsvKeepEveryFrameField() {
        val e = evidence()
        val parsed = Json.parse(Gate1.toJson(e)) as Map<*, *>

        assertEquals(Gate1.SCHEMA, parsed["schema"])
        assertEquals(8, (parsed["frames"] as List<*>).size)
        assertEquals(true, ((parsed["appEvaluation"] as Map<*, *>)["allPassed"]))
        val csv = Gate1.toCsv(e).lines().filter { it.isNotBlank() }
        assertEquals(9, csv.size)
        assertEquals(Gate1.CSV_HEADER.split(",").size, csv[1].split(",").size)
    }
}
