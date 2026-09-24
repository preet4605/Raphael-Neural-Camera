package com.neuralcamera.cameracore

import com.neuralcamera.deviceprofiles.CameraProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CameraProvider {
    fun getAvailableCameras(): List<CameraProfile>
    fun getCameraProfile(cameraId: String): CameraProfile?
}

interface CameraDeviceController {
    val state: StateFlow<CameraOperationalState>
    suspend fun openCamera(cameraId: String): Boolean
    suspend fun closeCamera()
}

interface CaptureSessionManager {
    suspend fun startRepeatingPreview(): Boolean
    suspend fun stopRepeating()
    suspend fun triggerBurstCapture(frameCount: Int, bracketSteps: List<Int> = emptyList()): List<CameraFrame>
}

interface FrameSource {
    val frameStream: Flow<CameraFrame>
}

interface FrameRepository {
    fun pushFrame(frame: CameraFrame)
    fun getLatestFrame(): CameraFrame?
    fun getRecentFrames(count: Int): List<CameraFrame>
    fun clear()
    fun currentResidencyBytes(): Long
}

interface FrameSynchronizer {
    fun synchronizeFrameWithSensors(
        frame: CameraFrame,
        gyroSample: FloatArray?,
        accelSample: FloatArray?
    ): CameraFrame
}

interface MetadataRepository {
    fun recordMetadata(metadata: FrameMetadata)
    fun getMetadata(frameSequence: Long): FrameMetadata?
    fun clear()
}
