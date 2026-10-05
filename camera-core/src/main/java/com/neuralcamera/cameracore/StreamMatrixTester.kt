package com.neuralcamera.cameracore

import android.hardware.camera2.CameraCharacteristics
import com.neuralcamera.deviceprofiles.StreamTestResult
import com.neuralcamera.deviceprofiles.StreamTestResultState

/**
 * Development stream matrix testing engine adhering to Section 8.
 * Evaluates candidate stream combinations against advertised hardware capabilities
 * and verifies their validity without confusing SUPPORTED, SESSION_CREATION_SUCCESS,
 * ACTUAL_CAPTURE_SUCCESS, and FAILED states.
 */
class StreamMatrixTester(
    private val characteristics: CameraCharacteristics,
    private val sessionPlanner: CameraSessionPlanner = CameraSessionPlanner(characteristics)
) {

    /**
     * Executes test matrix across safe candidate combinations.
     */
    fun runMatrixEvaluation(): List<StreamTestResult> {
        val results = mutableListOf<StreamTestResult>()

        val candidates = listOf(
            Triple("Preview Only", SessionTargetUseCase.PREVIEW_ONLY, listOf("PREVIEW")),
            Triple("Preview + YUV", SessionTargetUseCase.PREVIEW_WITH_ANALYSIS, listOf("PREVIEW", "YUV_420_888")),
            Triple("Preview + JPEG", SessionTargetUseCase.PREVIEW_WITH_STILL_JPEG, listOf("PREVIEW", "JPEG")),
            Triple("Preview + RAW", SessionTargetUseCase.PREVIEW_WITH_STILL_RAW, listOf("PREVIEW", "RAW_SENSOR")),
            Triple("Preview + YUV + JPEG", SessionTargetUseCase.PREVIEW_WITH_ANALYSIS_AND_STILL, listOf("PREVIEW", "YUV_420_888", "JPEG")),
            Triple("Preview + Video", SessionTargetUseCase.VIDEO_RECORDING, listOf("PREVIEW", "VIDEO"))
        )

        for ((name, useCase, streamNames) in candidates) {
            val startTime = System.currentTimeMillis()
            val planned = sessionPlanner.planSession(useCase)

            if (!planned.isSupportedByHardware) {
                results.add(
                    StreamTestResult(
                        combinationName = name,
                        streams = streamNames,
                        state = StreamTestResultState.FAILED,
                        latencyMs = System.currentTimeMillis() - startTime,
                        errorMessage = planned.validationReason
                    )
                )
                continue
            }

            // Verify formats against advertised configuration
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val formatsSupported = planned.streams.all { stream ->
                streamMap?.isOutputSupportedFor(stream.format) == true
            }

            val state = if (formatsSupported) {
                // In Phase 1 static verification, candidate is validated against StreamConfigurationMap
                StreamTestResultState.SUPPORTED
            } else {
                StreamTestResultState.FAILED
            }

            results.add(
                StreamTestResult(
                    combinationName = name,
                    streams = streamNames,
                    state = state,
                    latencyMs = System.currentTimeMillis() - startTime,
                    errorMessage = if (state == StreamTestResultState.FAILED) "One or more streams unsupported in format map" else null
                )
            )
        }

        return results
    }
}
