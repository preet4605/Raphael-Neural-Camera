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
        val reference = frames.last().metadata

        // 3. Process frames through baseline pipeline (classical only; no neural backend is verified)
        val result = app.imagePipeline.processFrames(
            frames = frames,
            targetWidth = 1920,
            targetHeight = 1080,
            requestNeuralAcceleration = false
        )

        // 4. Save non-destructively. Bytes are unencoded planes, so the files are not labelled as JPG.
        app.mediaRepository.saveMediaBundle(
            mediaId = "shot_${System.currentTimeMillis()}",
            originalBytes = result.originalLumaPlane,
            masterBytes = result.masterRgbPlane,
            captureMetadataJson = """{"iso": ${reference.iso}, "exposure_ns": ${reference.exposureTimeNs}}""",
            processingMetadataJson = """{"pipeline": "${result.appliedPipelineName}", "guard": "${result.realityGuardDecision.action}"}""",
            format = "raw"
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
