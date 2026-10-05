package com.neuralcamera.cameracore.proof

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RawBurstConfig(
    /** Null: pick a back camera that advertises RAW_SENSOR at [width] x [height], preferring a physical camera. */
    val cameraId: String? = null,
    val width: Int = Gate1.FULL_RES_WIDTH,
    val height: Int = Gate1.FULL_RES_HEIGHT,
    val frameCount: Int = Gate1.MIN_FRAMES,
    val outputDir: File,
    val timeoutMs: Long = 60_000L
)

/**
 * Gate 1 harness: captures a RAW_SENSOR burst through Camera2 as an ordinary app and records, per frame, everything the
 * gate needs. It never drops, substitutes or synthesizes frames: a frame that does not arrive is recorded as dropped.
 * Persists one DNG per frame (DngCreator) and fsyncs it. Blocking; call off the main thread. Needs CAMERA permission.
 *
 * Design notes:
 * - Frames are read with acquireNextImage() (never acquireLatestImage()), with maxImages == frameCount so the HAL never
 *   stalls while the app holds all frames of the burst until they are written.
 * - Images pair with results by SENSOR_TIMESTAMP; requests are identified by their tag, not by equality.
 * - The full-resolution RAW size is requested in whichever sensor pixel mode advertises it (DEFAULT, else
 *   MAXIMUM_RESOLUTION). A maximum-resolution request can only target maximum-resolution streams, so the session holds
 *   just this RAW stream (no preview).
 */
class RawBurstRecorder(private val context: Context) {

    private class Arrived(val image: Image, val arrivalNs: Long)
    private class Completed(val result: TotalCaptureResult, val arrivalNs: Long, val sensorTimestamp: Long?)

    @SuppressLint("MissingPermission")
    fun record(config: RawBurstConfig, processStartElapsedRealtimeMs: Long?, log: (String) -> Unit = {}): Gate1RunEvidence {
        val runId = UUID.randomUUID().toString()
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun event(message: String) {
            events.add(message)
            log(message)
        }
        val n = config.frameCount
        val frames = arrayOfNulls<RawFrameRecord>(n)
        val settled = BooleanArray(n)
        val frameDone = CountDownLatch(n)
        val lock = Any()
        val arrived = HashMap<Long, Arrived>()
        val completed = HashMap<Long, Completed>()
        val tagByTimestamp = HashMap<Long, Int>()

        var cameraInfo: Map<String, Any?> = emptyMap()
        var pixelModeName = "UNKNOWN"
        var timestampRealtime: Boolean? = null
        var failure: String? = null
        var finishedNormally = false

        val cameraThread = HandlerThread("gate1-camera").also { it.start() }
        val handler = Handler(cameraThread.looper)
        val cameraExecutor = Executor { handler.post(it) }
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "gate1-io") }
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var session: CameraCaptureSession? = null

        fun settle(index: Int, record: RawFrameRecord) {
            synchronized(lock) {
                if (settled[index]) return
                settled[index] = true
                frames[index] = record
            }
            frameDone.countDown()
        }

        try {
            config.outputDir.mkdirs()
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chosen = selectCamera(manager, config, ::event)
            val cameraId = chosen.first
            val chars = chosen.second
            pixelModeName = if (chosen.third == CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT) "DEFAULT" else "MAXIMUM_RESOLUTION"
            timestampRealtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            cameraInfo = describeCamera(cameraId, chars, chosen.third)
            event("INFO camera=$cameraId pixelMode=$pixelModeName timestampRealtime=$timestampRealtime")

            // Open the camera.
            val opened = CountDownLatch(1)
            var openError: String? = null
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    openError = "camera disconnected"
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError = "camera error $error"
                    opened.countDown()
                }
            }, handler)
            if (!opened.await(10, TimeUnit.SECONDS) || device == null) {
                throw IllegalStateException(openError ?: "timed out opening camera $cameraId")
            }

            // One RAW reader holding the whole burst.
            val imageReader = ImageReader.newInstance(config.width, config.height, ImageFormat.RAW_SENSOR, n)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ r ->
                while (true) {
                    val image = try {
                        r.acquireNextImage()
                    } catch (e: IllegalStateException) {
                        event("ERROR acquireNextImage: ${e.message}")
                        null
                    } ?: break
                    val arrival = SystemClock.elapsedRealtimeNanos()
                    synchronized(lock) {
                        arrived[image.timestamp] = Arrived(image, arrival)
                        pairAndProcess(image.timestamp, arrived, completed, tagByTimestamp, ioExecutor, chars, config, frames, settled, ::event, ::settle)
                    }
                }
            }, handler)

            // Session with just the RAW stream.
            val output = OutputConfiguration(imageReader.surface)
            if (chosen.third != CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "maximum-resolution mode needs API 31" }
                output.addSensorPixelModeUsed(chosen.third)
            }
            val configured = CountDownLatch(1)
            var sessionError: String? = null
            device!!.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(output), cameraExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            configured.countDown()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            sessionError = "createCaptureSession: onConfigureFailed"
                            configured.countDown()
                        }
                    }
                )
            )
            if (!configured.await(15, TimeUnit.SECONDS) || session == null) {
                throw IllegalStateException(sessionError ?: "timed out configuring the capture session")
            }

            // The burst.
            val requests = (0 until n).map { index ->
                device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    setTag(index)
                    if (chosen.third != CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT) {
                        set(CaptureRequest.SENSOR_PIXEL_MODE, chosen.third)
                    }
                }.build()
            }
            val sequenceDone = CountDownLatch(1)
            session!!.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val arrival = SystemClock.elapsedRealtimeNanos()
                    val index = request.tag as Int
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    synchronized(lock) {
                        if (ts != null) {
                            completed[ts] = Completed(result, arrival, ts)
                            tagByTimestamp[ts] = index
                            pairAndProcess(ts, arrived, completed, tagByTimestamp, ioExecutor, chars, config, frames, settled, ::event, ::settle)
                        } else {
                            event("ERROR result for index=$index has no SENSOR_TIMESTAMP")
                            settle(index, RawFrameRecord(index, dropped = true, droppedReason = "result without sensor timestamp"))
                        }
                    }
                }

                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val index = request.tag as Int
                    val reason = "capture failed reason=${failure.reason} imageCaptured=${failure.wasImageCaptured()} frame=${failure.frameNumber}"
                    event("FAILED index=$index $reason")
                    settle(index, RawFrameRecord(index, frameNumber = failure.frameNumber, dropped = true, droppedReason = reason))
                }

                override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    val index = request.tag as Int
                    event("LOST index=$index frame=$frameNumber buffer lost")
                    settle(index, RawFrameRecord(index, frameNumber = frameNumber, dropped = true, droppedReason = "buffer lost"))
                }

                override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    event("INFO sequence $sequenceId completed, last frame number $frameNumber")
                    sequenceDone.countDown()
                }

                override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                    event("FAILED sequence $sequenceId aborted")
                    sequenceDone.countDown()
                }
            }, handler)

            if (!frameDone.await(config.timeoutMs, TimeUnit.MILLISECONDS)) {
                event("FAILED timed out waiting for ${frameDone.count} of $n frames")
            }
            finishedNormally = true
        } catch (e: CameraAccessException) {
            failure = "CameraAccessException: ${e.message} (reason ${e.reason})"
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            ioExecutor.shutdown()
            ioExecutor.awaitTermination(120, TimeUnit.SECONDS)
            synchronized(lock) {
                arrived.forEach { (ts, a) ->
                    event("INFO unmatched image timestamp=$ts (no capture result carried this SENSOR_TIMESTAMP)")
                    runCatching { a.image.close() }
                }
                completed.keys.forEach { ts -> event("INFO unmatched capture result timestamp=$ts (no image carried this timestamp)") }
                arrived.clear()
            }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            cameraThread.quitSafely()
        }

        // Anything that never settled did not arrive.
        val finalFrames = (0 until n).map { i ->
            frames[i] ?: RawFrameRecord(i, dropped = true, droppedReason = "no image and result arrived before the timeout")
        }
        val evidence = Gate1RunEvidence(
            runId = runId,
            processStartElapsedRealtimeMs = processStartElapsedRealtimeMs,
            device = emptyMap(),
            app = describeApp(),
            camera = cameraInfo,
            requestedFrames = n,
            width = config.width,
            height = config.height,
            sensorPixelMode = pixelModeName,
            timestampSourceRealtime = timestampRealtime,
            frames = finalFrames,
            events = events.toList(),
            completedNormally = finishedNormally && failure == null,
            failure = failure
        )
        return evidence
    }

    /** Must be called with the lock held. Processes a frame once both its image and its result are present. */
    private fun pairAndProcess(
        timestamp: Long,
        arrived: HashMap<Long, Arrived>,
        completed: HashMap<Long, Completed>,
        tagByTimestamp: HashMap<Long, Int>,
        ioExecutor: java.util.concurrent.ExecutorService,
        chars: CameraCharacteristics,
        config: RawBurstConfig,
        frames: Array<RawFrameRecord?>,
        settled: BooleanArray,
        event: (String) -> Unit,
        settle: (Int, RawFrameRecord) -> Unit
    ) {
        val arrivedImage = arrived[timestamp] ?: return
        val done = completed[timestamp] ?: return
        val index = tagByTimestamp[timestamp] ?: return
        arrived.remove(timestamp)
        completed.remove(timestamp)
        ioExecutor.execute {
            val image = arrivedImage.image
            try {
                val plane = image.planes[0]
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(plane.buffer.duplicate())
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val file = File(config.outputDir, "frame_%02d.dng".format(index))
                DngCreator(chars, done.result).use { dng ->
                    FileOutputStream(file).use { out ->
                        dng.writeImage(out, image)
                        out.flush()
                        out.fd.sync()
                    }
                }
                settle(
                    index,
                    RawFrameRecord(
                        requestIndex = index,
                        frameNumber = done.result.frameNumber,
                        sensorTimestampNs = done.sensorTimestamp,
                        imageTimestampNs = image.timestamp,
                        imageArrivalElapsedNs = arrivedImage.arrivalNs,
                        resultArrivalElapsedNs = done.arrivalNs,
                        width = image.width,
                        height = image.height,
                        format = image.format,
                        exposureTimeNs = done.result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        iso = done.result.get(CaptureResult.SENSOR_SENSITIVITY),
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        planeBytes = plane.buffer.capacity().toLong(),
                        pixelSha256 = sha,
                        dngFile = file.name,
                        dngBytes = file.length()
                    )
                )
            } catch (t: Throwable) {
                event("FAILED index=$index processing: ${t.javaClass.simpleName}: ${t.message}")
                settle(index, RawFrameRecord(index, dropped = true, droppedReason = "processing failed: ${t.message}"))
            } finally {
                runCatching { image.close() }
            }
        }
    }

    private fun selectCamera(manager: CameraManager, config: RawBurstConfig, event: (String) -> Unit): Triple<String, CameraCharacteristics, Int> {
        data class Candidate(val id: String, val chars: CameraCharacteristics, val mode: Int, val logical: Boolean)
        val candidates = mutableListOf<Candidate>()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val modes = rawModesFor(chars, config.width, config.height)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
            val logical = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            event("INFO candidate id=$id facing=$facing logical=$logical rawModes=$modes caps=$capabilities")
            if (config.cameraId != null && id != config.cameraId) continue
            if (config.cameraId == null && facing != CameraCharacteristics.LENS_FACING_BACK) continue
            if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) || modes.isEmpty()) continue
            candidates.add(Candidate(id, chars, modes.first(), logical))
        }
        val pick = candidates.firstOrNull { !it.logical } ?: candidates.firstOrNull()
            ?: throw IllegalStateException("no camera advertises RAW_SENSOR ${config.width}x${config.height}")
        return Triple(pick.id, pick.chars, pick.mode)
    }

    /** Sensor pixel modes in which this camera advertises RAW_SENSOR at the requested size, DEFAULT first. */
    private fun rawModesFor(chars: CameraCharacteristics, width: Int, height: Int): List<Int> {
        val modes = mutableListOf<Int>()
        val defaultSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (defaultSizes?.any { it.width == width && it.height == height } == true) modes.add(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maxSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (maxSizes?.any { it.width == width && it.height == height } == true) modes.add(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
        return modes
    }

    private fun describeCamera(id: String, chars: CameraCharacteristics, pixelMode: Int): Map<String, Any?> {
        val sizes = { m: android.hardware.camera2.params.StreamConfigurationMap? ->
            m?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { "${it.width}x${it.height}" }
        }
        return linkedMapOf(
            "id" to id,
            "lensFacing" to chars.get(CameraCharacteristics.LENS_FACING),
            "hardwareLevel" to chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            "capabilities" to chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList(),
            "physicalCameraIds" to chars.physicalCameraIds.toList(),
            "pixelArraySize" to chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString(),
            "activeArraySize" to chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toString(),
            "activeArraySizeMaxResolution" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.toString() else null,
            "timestampSource" to chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
            "whiteLevel" to chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            "rawSizesDefaultMode" to sizes(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)),
            "rawSizesMaxResolutionMode" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                sizes(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)) else null,
            "selectedSensorPixelMode" to pixelMode
        )
    }

    private fun describeApp(): Map<String, Any?> {
        val info = context.applicationInfo
        val granted = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            pi.requestedPermissions?.filterIndexed { i, _ ->
                (pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }
        } catch (e: Exception) {
            null
        }
        return linkedMapOf(
            "packageName" to context.packageName,
            "uid" to Process.myUid(),
            "isSystemApp" to ((info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0),
            "targetSdk" to info.targetSdkVersion,
            "grantedPermissions" to granted
        )
    }
}
