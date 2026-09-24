package com.neuralcamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.FramePlane
import com.neuralcamera.capture.CameraShootingMode
import com.neuralcamera.capture.MotionVector
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.ui.CameraUIState
import com.neuralcamera.ui.NeuralCameraScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as NeuralCameraApplication

        setContent {
            var uiState by remember {
                mutableStateOf(
                    CameraUIState(
                        activeMode = CameraShootingMode.AUTO,
                        activeZoomFactor = 1.0f,
                        isNeuralActive = true,
                        neuralBackendName = "QNN HTP NPU (Qualcomm)",
                        latencyMs = 28L,
                        memoryUsageMb = 184L,
                        thermalStatus = "NORMAL",
                        realityGuardState = "PROTECTING (1.00)",
                        showDiagnostics = false
                    )
                )
            }

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
                onShutterPressed = {
                    lifecycleScope.launch {
                        uiState = uiState.copy(isCapturing = true)
                        // Trigger planned capture through runtime
                        val plan = app.capturePlanner.planCapture(
                            mode = uiState.activeMode,
                            sceneLuminanceLux = 120f,
                            motion = MotionVector(0.01f, 0.01f, 0.01f, true),
                            deviceProfile = app.deviceProfileRepository.getActiveProfile()
                        )

                        // Synthesize acquired frames
                        val dummyFrames = (1..plan.temporalFrameCount).map { seq ->
                            CameraFrame(
                                frameId = "shot_${System.currentTimeMillis()}_$seq",
                                format = if (plan.useRawStream) "RAW_SENSOR" else "YUV_420_888",
                                width = 1920,
                                height = 1080,
                                planes = listOf(FramePlane(ByteArray(1920 * 1080) { 128.toByte() }, 1, 1920)),
                                metadata = FrameMetadata(
                                    frameSequence = seq.toLong(),
                                    timestampNs = System.nanoTime(),
                                    exposureTimeNs = plan.exposureTimeNs,
                                    iso = plan.iso,
                                    focalLengthMm = 5.59f,
                                    focusDistanceMeters = 1.5f,
                                    apertureFNumber = 1.6f,
                                    lensFacing = LensFacing.BACK_WIDE,
                                    physicalCameraId = plan.targetCameraId,
                                    sensorOrientation = 90
                                )
                            )
                        }

                        // Run Image Pipeline
                        val result = app.imagePipeline.processFrames(
                            frames = dummyFrames,
                            targetWidth = 1920,
                            targetHeight = 1080,
                            requestNeuralAcceleration = true
                        )

                        // Save Original + Master non-destructively
                        app.mediaRepository.saveMediaBundle(
                            mediaId = "shot_${System.currentTimeMillis()}",
                            originalBytes = result.originalLumaPlane,
                            masterBytes = result.masterRgbPlane,
                            captureMetadataJson = """{"iso": ${plan.iso}, "exposure_ns": ${plan.exposureTimeNs}}""",
                            processingMetadataJson = """{"pipeline": "${result.appliedPipelineName}", "guard": "${result.realityGuardDecision.action}"}""",
                            format = "JPG"
                        )

                        uiState = uiState.copy(
                            isCapturing = false,
                            latencyMs = result.metrics.latencyMs,
                            realityGuardState = "${result.realityGuardDecision.action} (${String.format("%.2f", result.realityGuardDecision.blendRatio)})"
                        )
                    }
                },
                onToggleDiagnostics = {
                    uiState = uiState.copy(showDiagnostics = !uiState.showDiagnostics)
                }
            )
        }
    }
}
