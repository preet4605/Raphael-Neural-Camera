package com.neuralcamera.cameracore.threading

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Threading architecture contract mandated by Section 9 of the Neural Camera Constitution.
 * Prohibits heavy operations, inference, or disk IO on the Main thread.
 */
interface CameraDispatchers {
    val main: CoroutineDispatcher          // UI rendering & state observation only
    val camera: CoroutineDispatcher        // Camera2 device callbacks & session management
    val frameAnalysis: CoroutineDispatcher // Fast preview frame analysis & motion tracking
    val neuralInference: CoroutineDispatcher // AI / Inference execution off-thread
    val imageProcessing: CoroutineDispatcher // Computational ISP fusion, tone mapping
    val io: CoroutineDispatcher            // Disk storage, database & MediaStore operations
    val nativeProcessing: CoroutineDispatcher // Direct JNI & native hardware acceleration
}

/**
 * Default implementation mapping to structured Kotlin Coroutine dispatchers.
 */
object DefaultCameraDispatchers : CameraDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val camera: CoroutineDispatcher = Dispatchers.Default
    override val frameAnalysis: CoroutineDispatcher = Dispatchers.Default
    override val neuralInference: CoroutineDispatcher = Dispatchers.Default
    override val imageProcessing: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val nativeProcessing: CoroutineDispatcher = Dispatchers.Default
}
