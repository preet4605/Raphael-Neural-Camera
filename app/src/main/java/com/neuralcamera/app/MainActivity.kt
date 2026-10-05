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
                    uiState = uiState.copy(activeMode = selectedMode)
                    app.telemetryLogger.logEvent("MODE_CHANGED", mapOf("mode" to selectedMode.name))
                },
                onZoomSelected = { zoom ->
                    uiState = uiState.copy(activeZoomFactor = zoom)
                    app.telemetryLogger.logEvent("ZOOM_CHANGED", mapOf("zoom" to zoom))
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
                        uiState = uiState.copy(isCapturing = true, statusMessage = null)
                        try {
                            captureAndSave()
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
    private suspend fun captureAndSave() {
        // 1. Plan capture using UniversalCapturePlanner
        val plan = app.capturePlanner.planCapture(
            mode = uiState.activeMode,
            sceneLuminanceLux = 120f,
            motion = MotionVector(0.01f, 0.01f, 0.01f, true),
            deviceProfile = app.deviceProfileRepository.getActiveProfile()
        )

        // 2. Acquire real hardware frames
        val frames = app.cameraController.triggerBurstCapture(plan.temporalFrameCount)
        check(frames.isNotEmpty()) { "Capture returned no frames; nothing was saved." }
        app.telemetryLogger.logEvent(
            "BURST_CAPTURED",
            mapOf("requested" to plan.temporalFrameCount, "received" to frames.size, "format" to frames.first().format, "size" to "${frames.first().width}x${frames.first().height}")
        )
        val reference = frames.last().metadata

        // 3. Process frames through baseline pipeline (classical only; no neural backend is verified)
        val result = app.imagePipeline.processFrames(
            frames = frames,
            targetWidth = frames.first().width,
            targetHeight = frames.first().height,
            requestNeuralAcceleration = false
        )

        // 4. Encode and save non-destructively. The baseline pipeline is luma only, so both files are real grayscale
        // JPEGs (decodable by any viewer), not colour. A DNG needs RAW frames, which this YUV path does not produce.
        val exif = JpegExif(
            orientation = when (reference.sensorOrientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 },
            exposureTimeSeconds = reference.exposureTimeNs.takeIf { it > 0 }?.let { it / 1e9 },
            iso = reference.iso.takeIf { it > 0 },
            software = "Raphael Neural Camera"
        )
        val pixelCount = result.outputWidth * result.outputHeight
        val masterGray = ByteArray(pixelCount) { result.masterRgbPlane[it * 3] }
        app.mediaRepository.saveMediaBundle(
            mediaId = "shot_${System.currentTimeMillis()}",
            originalBytes = JpegEncoder.encodeGray(result.originalLumaPlane, result.outputWidth, result.outputHeight, 95, exif),
            masterBytes = JpegEncoder.encodeGray(masterGray, result.outputWidth, result.outputHeight, 95, exif),
            captureMetadataJson = """{"iso": ${reference.iso}, "exposure_ns": ${reference.exposureTimeNs}, "source_format": "${frames.first().format}", "frames": ${frames.size}}""",
            processingMetadataJson = """{"pipeline": "${result.appliedPipelineName}", "guard": "${result.realityGuardDecision.action}", "colour": false}""",
            format = "jpg"
        )

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
                uiState = uiState.copy(statusMessage = error)
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
