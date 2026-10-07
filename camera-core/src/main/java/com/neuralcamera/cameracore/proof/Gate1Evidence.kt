package com.neuralcamera.cameracore.proof

import com.neuralcamera.benchmarks.Json

/** One frame of a RAW burst as the app observed it. Unknown values are null, never defaults. */
data class RawFrameRecord(
    /** 0-based index of the request this frame answers (the request tag). */
    val requestIndex: Int,
    val frameNumber: Long? = null,
    /** SENSOR_TIMESTAMP from the capture result. */
    val sensorTimestampNs: Long? = null,
    /** Image.getTimestamp(). */
    val imageTimestampNs: Long? = null,
    /** SystemClock.elapsedRealtimeNanos() when the image reached onImageAvailable. */
    val imageArrivalElapsedNs: Long? = null,
    /** SystemClock.elapsedRealtimeNanos() when the capture result reached onCaptureCompleted. */
    val resultArrivalElapsedNs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val format: Int? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val rowStride: Int? = null,
    val pixelStride: Int? = null,
    val planeBytes: Long? = null,
    val pixelSha256: String? = null,
    val dngFile: String? = null,
    val dngBytes: Long? = null,
    val dropped: Boolean = false,
    val droppedReason: String? = null
)

/** Everything one Gate 1 run measured, as raw data an independent checker can re-evaluate. */
data class Gate1RunEvidence(
    val runId: String,
    val processStartElapsedRealtimeMs: Long?,
    val device: Map<String, String>,
    val app: Map<String, Any?>,
    val camera: Map<String, Any?>,
    val requestedFrames: Int,
    val width: Int,
    val height: Int,
    val sensorPixelMode: String,
    val timestampSourceRealtime: Boolean?,
    val frames: List<RawFrameRecord>,
    val events: List<String>,
    val completedNormally: Boolean,
    val failure: String?
)

data class Gate1Criterion(val name: String, val passed: Boolean, val detail: String)

object Gate1 {
    const val SCHEMA = "raphael.gate1.run/1"
    const val IMAGE_FORMAT_RAW_SENSOR = 0x20
    const val MIN_FRAMES = 8
    const val FULL_RES_WIDTH = 8192
    const val FULL_RES_HEIGHT = 6144

    /** Re-evaluates the Gate 1 criteria that can be decided from one run's raw records. */
    fun evaluate(e: Gate1RunEvidence): List<Gate1Criterion> {
        val c = mutableListOf<Gate1Criterion>()
        fun add(name: String, ok: Boolean, detail: String) = c.add(Gate1Criterion(name, ok, detail))
        val frames = e.frames.sortedBy { it.requestIndex }

        add("run_completed_normally", e.completedNormally && e.failure == null, e.failure ?: "no failure recorded")
        add("requested_at_least_8_frames", e.requestedFrames >= MIN_FRAMES, "requested ${e.requestedFrames}")
        add("all_requested_frames_present", frames.size == e.requestedFrames && frames.map { it.requestIndex } == (0 until e.requestedFrames).toList(),
            "${frames.size} records for ${e.requestedFrames} requests")
        add("target_is_full_resolution", e.width == FULL_RES_WIDTH && e.height == FULL_RES_HEIGHT, "${e.width}x${e.height}")
        add("every_frame_full_resolution", frames.isNotEmpty() && frames.all { it.width == FULL_RES_WIDTH && it.height == FULL_RES_HEIGHT },
            frames.joinToString { "${it.width}x${it.height}" })
        add("every_frame_raw_sensor_format", frames.isNotEmpty() && frames.all { it.format == IMAGE_FORMAT_RAW_SENSOR },
            frames.joinToString { it.format.toString() })
        add("zero_drops", frames.none { it.dropped } && e.events.none { it.startsWith("LOST") || it.startsWith("FAILED") },
            "dropped=${frames.count { it.dropped }} events=${e.events.size}")

        val numbers = frames.map { it.frameNumber }
        add("frame_numbers_consecutive", numbers.none { it == null } && numbers.zipWithNext().all { (a, b) -> b == a!! + 1 }, numbers.toString())
        val ts = frames.map { it.sensorTimestampNs }
        add("sensor_timestamps_strictly_monotonic", ts.none { it == null } && ts.zipWithNext().all { (a, b) -> b!! > a!! }, ts.toString())
        add("image_timestamp_equals_result_timestamp",
            frames.isNotEmpty() && frames.all { it.imageTimestampNs != null && it.imageTimestampNs == it.sensorTimestampNs },
            "image vs result timestamps")
        add("metadata_valid",
            frames.isNotEmpty() && frames.all { (it.exposureTimeNs ?: 0) > 0 && (it.iso ?: 0) > 0 && (it.rowStride ?: 0) > 0 && (it.pixelStride ?: 0) > 0 },
            "exposure/ISO/stride present and positive")
        add("plane_covers_full_frame",
            frames.isNotEmpty() && frames.all { (it.planeBytes ?: 0) >= (it.rowStride ?: Int.MAX_VALUE).toLong() * (it.height ?: Int.MAX_VALUE) },
            "plane bytes >= rowStride * height")
        val hashes = frames.map { it.pixelSha256 }
        add("no_duplicate_frames", hashes.none { it == null } && hashes.toSet().size == hashes.size && hashes.isNotEmpty(),
            "${hashes.toSet().size} distinct of ${hashes.size}")
        add("arrival_not_before_capture",
            e.timestampSourceRealtime != true || frames.all { (it.imageArrivalElapsedNs ?: -1) >= (it.sensorTimestampNs ?: Long.MAX_VALUE) },
            if (e.timestampSourceRealtime == true) "REALTIME timestamp source: arrival >= sensor timestamp" else "timestamp source not REALTIME: not checked")
        add("dng_written_for_every_frame",
            frames.isNotEmpty() && frames.all { it.dngFile != null && (it.dngBytes ?: 0) >= it.width!!.toLong() * it.height!! * 2 },
            frames.joinToString { "${it.dngBytes}" })
        val isSystem = e.app["isSystemApp"]
        val uid = (e.app["uid"] as? Number)?.toLong()
        add("ordinary_non_system_app", isSystem == false && uid != null && uid >= 10_000, "isSystemApp=$isSystem uid=$uid")
        return c
    }

    fun toMap(e: Gate1RunEvidence, criteria: List<Gate1Criterion>): Map<String, Any?> = linkedMapOf(
        "schema" to SCHEMA,
        "runId" to e.runId,
        "processStartElapsedRealtimeMs" to e.processStartElapsedRealtimeMs,
        "device" to e.device,
        "app" to e.app,
        "camera" to e.camera,
        "target" to mapOf("width" to e.width, "height" to e.height, "requestedFrames" to e.requestedFrames, "sensorPixelMode" to e.sensorPixelMode),
        "timestampSourceRealtime" to e.timestampSourceRealtime,
        "frames" to e.frames.sortedBy { it.requestIndex }.map { f ->
            linkedMapOf(
                "requestIndex" to f.requestIndex, "frameNumber" to f.frameNumber, "sensorTimestampNs" to f.sensorTimestampNs,
                "imageTimestampNs" to f.imageTimestampNs, "imageArrivalElapsedNs" to f.imageArrivalElapsedNs,
                "resultArrivalElapsedNs" to f.resultArrivalElapsedNs, "width" to f.width, "height" to f.height, "format" to f.format,
                "exposureTimeNs" to f.exposureTimeNs, "iso" to f.iso, "rowStride" to f.rowStride, "pixelStride" to f.pixelStride,
                "planeBytes" to f.planeBytes, "pixelSha256" to f.pixelSha256, "dngFile" to f.dngFile, "dngBytes" to f.dngBytes,
                "dropped" to f.dropped, "droppedReason" to f.droppedReason
            )
        },
        "events" to e.events,
        "completedNormally" to e.completedNormally,
        "failure" to e.failure,
        "appEvaluation" to mapOf(
            "note" to "Informational. tools/proof/check_gate1.py re-derives the verdict from raw data and the DNG files.",
            "criteria" to criteria.map { mapOf("name" to it.name, "passed" to it.passed, "detail" to it.detail) },
            "allPassed" to criteria.all { it.passed }
        )
    )

    fun toJson(e: Gate1RunEvidence): String = Json.stringify(toMap(e, evaluate(e)))

    /** Writes gate1_report.json and frames.csv next to the DNG files. */
    fun write(e: Gate1RunEvidence, dir: java.io.File) {
        dir.mkdirs()
        java.io.File(dir, "gate1_report.json").writeText(toJson(e))
        java.io.File(dir, "frames.csv").writeText(toCsv(e))
    }

    const val CSV_HEADER = "request_index,frame_number,sensor_timestamp_ns,image_timestamp_ns,image_arrival_elapsed_ns," +
        "result_arrival_elapsed_ns,exposure_time_ns,iso,width,height,format,dropped,pixel_sha256,dng_file,dng_bytes"

    fun toCsv(e: Gate1RunEvidence): String = buildString {
        appendLine(CSV_HEADER)
        for (f in e.frames.sortedBy { it.requestIndex }) {
            appendLine(
                listOf(
                    f.requestIndex, f.frameNumber, f.sensorTimestampNs, f.imageTimestampNs, f.imageArrivalElapsedNs,
                    f.resultArrivalElapsedNs, f.exposureTimeNs, f.iso, f.width, f.height, f.format, f.dropped, f.pixelSha256,
                    f.dngFile, f.dngBytes
                ).joinToString(",") { it?.toString() ?: "" }
            )
        }
    }
}
