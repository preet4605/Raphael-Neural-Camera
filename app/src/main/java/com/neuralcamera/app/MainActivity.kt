package com.neuralcamera.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.neuralcamera.capture.MotionVector
import com.neuralcamera.isp.encode.JpegEncoder
import com.neuralcamera.isp.encode.JpegExif
import com.neuralcamera.ui.CameraUIState
import com.neuralcamera.ui.NeuralCameraScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var app: NeuralCameraApplication
    private var activePreviewSurface: Surface? = null
    private var uiState by mutableStateOf(CameraUIState())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraPreviewIfReady()
        } else {
            uiState = uiState.copy(statusMessage = "Camera permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NeuralCameraApplication

        // Request runtime camera permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            NeuralCameraScreen(
                state = uiState,
                onModeSelected = { selectedMode ->
                    val frames = planFor(selectedMode).temporalFrameCount
                    uiState = uiState.copy(
                        activeMode = selectedMode,
                        statusMessage = null,
                        infoMessage = "${selectedMode.name}: $frames frame${if (frames == 1) "" else "s"} per shot " +
                            "(a mode currently only sets the burst length; exposure stays on camera auto)"
                    )
                    app.telemetryLogger.logEvent("MODE_CHANGED", mapOf("mode" to selectedMode.name, "frames" to frames))
                },
                onZoomSelected = { zoom ->
                    lifecycleScope.launch {
                        val applied = app.cameraController.applyZoom(zoom)
                        val range = app.cameraController.supportedZoomRange()
                        uiState = if (applied != null) {
                            uiState.copy(activeZoomFactor = zoom, supportedZoom = range, statusMessage = null, infoMessage = "Zoom ${"%.1f".format(applied)}x")
                        } else {
                            uiState.copy(
                                supportedZoom = range,
                                infoMessage = "${"%.1f".format(zoom)}x is not supported by this camera" +
                                    (range?.let { " (range ${"%.1f".format(it.start)}x to ${"%.1f".format(it.endInclusive)}x)" } ?: "")
                            )
                        }
                        app.telemetryLogger.logEvent("ZOOM_CHANGED", mapOf("requested" to zoom, "applied" to (applied ?: -1f)))
                    }
                },
                onSurfaceAvailable = { surface ->
                    activePreviewSurface = surface
                    startCameraPreviewIfReady()
                },
                onSurfaceDestroyed = {
                    activePreviewSurface = null
                    lifecycleScope.launch {
                        app.cameraController.closeCamera()
                    }
                },
                onShutterPressed = {
                    lifecycleScope.launch {
                        uiState = uiState.copy(isCapturing = true, statusMessage = null, infoMessage = "Capturing...")
                        try {
                            val summary = captureAndSave()
                            uiState = uiState.copy(infoMessage = summary)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            uiState = uiState.copy(statusMessage = e.message ?: e.javaClass.simpleName)
                        } finally {
                            uiState = uiState.copy(isCapturing = false)
                        }
                    }
                },
                onToggleDiagnostics = {
                    uiState = uiState.copy(showDiagnostics = !uiState.showDiagnostics)
                }
            )
        }
    }

    /**
     * Plans, captures, processes and saves one burst. Only frames returned by the camera are ever
     * processed: when capture yields nothing this throws and nothing is saved.
     */
    private fun planFor(mode: com.neuralcamera.capture.CameraShootingMode) = app.capturePlanner.planCapture(
        mode = mode,
        sceneLuminanceLux = 120f, // fixed placeholder: no light sensor reading is wired yet
        motion = MotionVector(0.01f, 0.01f, 0.01f, true), // fixed placeholder: no motion estimate is wired yet
        deviceProfile = app.deviceProfileRepository.getActiveProfile()
    )

    /** Inserts a JPEG into the system gallery (Pictures/Raphael); no storage permission is needed on API 30+. */
    private fun saveToGallery(name: String, jpeg: ByteArray): String? {
        var uri: android.net.Uri? = null
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Raphael")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val target = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            uri = target
            val stream = contentResolver.openOutputStream(target) ?: throw java.io.IOException("no output stream for $target")
            stream.use { it.write(jpeg) }
            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(target, values, null, null)
            "Pictures/Raphael/$name.jpg"
        } catch (e: Exception) {
            // Remove the pending row so a failed write never leaves a broken, half-written gallery entry.
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            null
        }
    }

    private suspend fun captureAndSave(): String {
        val started = System.currentTimeMillis()
        // 1. Plan capture using UniversalCapturePlanner
        val plan = planFor(uiState.activeMode)

        // 2. Acquire real hardware frames
        val frames = app.cameraController.triggerBurstCapture(plan.temporalFrameCount)
        check(frames.isNotEmpty()) { "Capture returned no frames; nothing was saved." }
        app.telemetryLogger.logEvent(
            "BURST_CAPTURED",
            mapOf("requested" to plan.temporalFrameCount, "received" to frames.size, "format" to frames.first().format, "size" to "${frames.first().width}x${frames.first().height}")
        )
        val reference = frames.last().metadata

        // 3. Process frames through baseline pipeline (classical only; no neural backend is verified)
        // Merge and encoding are CPU heavy: keep them off the main thread so the UI stays responsive.
        val result = withContext(Dispatchers.Default) {
            app.imagePipeline.processFrames(
                frames = frames,
                targetWidth = frames.first().width,
                targetHeight = frames.first().height,
                requestNeuralAcceleration = false
            )
        }

        // 4. Encode and save non-destructively. The master is a colour JPEG when the frames had chroma planes (luma is
        // merged, chroma comes from the reference frame), else grayscale; the original is the reference luma. A DNG
        // needs RAW frames, which this YUV path does not produce.
        val exif = JpegExif(
            orientation = when (reference.sensorOrientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 },
            exposureTimeSeconds = reference.exposureTimeNs.takeIf { it > 0 }?.let { it / 1e9 },
            iso = reference.iso.takeIf { it > 0 },
            software = "Raphael Neural Camera"
        )
        val pixelCount = result.outputWidth * result.outputHeight
        val mediaId = "shot_${System.currentTimeMillis()}"
        val master = withContext(Dispatchers.Default) {
            if (result.isColour) JpegEncoder.encodeRgb(result.masterRgbPlane, result.outputWidth, result.outputHeight, 95, exif)
            else JpegEncoder.encodeGray(ByteArray(pixelCount) { result.masterRgbPlane[it * 3] }, result.outputWidth, result.outputHeight, 95, exif)
        }
        val original = withContext(Dispatchers.Default) {
            JpegEncoder.encodeGray(result.originalLumaPlane, result.outputWidth, result.outputHeight, 95, exif)
        }
        withContext(Dispatchers.IO) {
            app.mediaRepository.saveMediaBundle(
                mediaId = mediaId,
                originalBytes = original,
                masterBytes = master,
                captureMetadataJson = """{"iso": ${reference.iso}, "exposure_ns": ${reference.exposureTimeNs}, "source_format": "${frames.first().format}", "frames": ${frames.size}}""",
                processingMetadataJson = """{"pipeline": "${result.appliedPipelineName}", "guard": "${result.realityGuardDecision.action}", "colour": ${result.isColour}}""",
                format = "jpg"
            )
        }
        val galleryPath = withContext(Dispatchers.IO) { saveToGallery(mediaId, master) }

        // 5. Update UI telemetry state from measured values
        uiState = uiState.copy(
            latencyMs = result.metrics.latencyMs,
            realityGuardState = "${result.realityGuardDecision.action} (${String.format("%.2f", result.realityGuardDecision.blendRatio)})",
            diagnosticsData = uiState.diagnosticsData.copy(
                iso = reference.iso,
                exposureTimeNs = reference.exposureTimeNs,
                frameNumber = reference.frameSequence,
                timestampNs = reference.timestampNs
            )
        )
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        return "Saved ${frames.size}-frame ${if (result.isColour) "colour" else "gray"} ${result.outputWidth}x${result.outputHeight} JPEG " +
            "in ${"%.1f".format(seconds)} s" + (galleryPath?.let { " -> $it" } ?: " (gallery save failed; copy is in app storage)")
    }

    private fun startCameraPreviewIfReady() {
        val surface = activePreviewSurface ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        lifecycleScope.launch {
            try {
                val error = when {
                    !app.cameraController.openCamera("0") -> "Camera failed to open."
                    !app.cameraController.configureSession(surface) -> "Camera session configuration failed."
                    !app.cameraController.startRepeatingPreview() -> "Preview failed to start."
                    else -> null
                }
                uiState = uiState.copy(statusMessage = error, supportedZoom = if (error == null) app.cameraController.supportedZoomRange() else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = uiState.copy(statusMessage = "Camera error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.sensorSynchronizer.startListening()
        startCameraPreviewIfReady()
    }

    override fun onPause() {
        super.onPause()
        app.sensorSynchronizer.stopListening()
        lifecycleScope.launch {
            app.cameraController.stopRepeating()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            app.cameraController.closeCamera()
        }
        app.cameraController.release()
    }
}
