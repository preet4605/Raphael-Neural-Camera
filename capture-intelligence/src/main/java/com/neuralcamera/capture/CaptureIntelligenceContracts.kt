package com.neuralcamera.capture

import com.neuralcamera.deviceprofiles.DeviceProfile

enum class CameraShootingMode {
    AUTO,
    PRO,
    MASTER,
    AUTHENTIC
}

data class MotionVector(
    val deltaX: Float,
    val deltaY: Float,
    val angularVelocityRadS: Float,
    val isTripodStable: Boolean
)

data class CapturePlan(
    val mode: CameraShootingMode,
    val targetCameraId: String,
    val temporalFrameCount: Int,
    val exposureTimeNs: Long,
    val iso: Int,
    val bracketStepsEv: List<Float> = emptyList(),
    val useRawStream: Boolean,
    val enableTemporalFusion: Boolean,
    val reason: String
)

interface MotionEstimator {
    fun estimateMotion(
        previousLuma: ByteArray?,
        currentLuma: ByteArray,
        width: Int,
        height: Int,
        gyroSample: FloatArray?
    ): MotionVector
}

interface SensorProvider {
    fun startListening()
    fun stopListening()
    fun getLatestGyroReading(): FloatArray?
    fun getLatestAccelReading(): FloatArray?
}

interface CapturePlanner {
    fun planCapture(
        mode: CameraShootingMode,
        sceneLuminanceLux: Float,
        motion: MotionVector,
        deviceProfile: DeviceProfile,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null
    ): CapturePlan
}
