package com.neuralcamera.cameracore

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.neuralcamera.cameracore.errors.CameraSystemError
import com.neuralcamera.cameracore.state.CameraState
import com.neuralcamera.cameracore.state.CameraStateMachine
import com.neuralcamera.deviceprofiles.CameraProfile
import com.neuralcamera.deviceprofiles.DiscoveredCameraProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-ready Camera2 Controller implementing Section 28 (State Machine Integration)
 * and Section 30 (Bounded Error Recovery).
 */
class RealCamera2Controller(
    private val context: Context,
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
    val stateMachine: CameraStateMachine = CameraStateMachine()
) : CameraDeviceController, CaptureSessionManager, CameraProvider {

    private val mutex = Mutex()
    private val _operationalState = MutableStateFlow(CameraOperationalState.UNINITIALIZED)
    override val state: StateFlow<CameraOperationalState> = _operationalState.asStateFlow()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var activeCameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var previewSurface: Surface? = null
    private var burstReader: ImageReader? = null
    @Volatile private var burstSize: android.util.Size? = null
    /** Collector for the burst in flight; the reader listener hands every arriving image to it. */
    @Volatile private var activeCollector: BurstCollector<CopiedYuv, TotalCaptureResult>? = null
    private var analysisImageReader: ImageReaderManager? = null

    @Volatile private var zoomRatio = 1.0f

    /** Zoom range the active camera accepts, or null when the camera is closed or has no zoom-ratio control. */
    fun supportedZoomRange(): ClosedFloatingPointRange<Float>? {
        val id = activeCameraId ?: return null
        val r = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return null
        return r.lower..r.upper
    }

    /**
     * Applies [ratio] to the preview and to later bursts. Returns the ratio now in effect, or null when the camera does
     * not support that ratio (nothing changes then). Optical lens switching happens inside a logical multi-camera.
     */
    suspend fun applyZoom(ratio: Float): Float? = mutex.withLock {
        val range = supportedZoomRange() ?: return null
        if (ratio < range.start - 1e-3f || ratio > range.endInclusive + 1e-3f) return null
        zoomRatio = ratio.coerceIn(range.start, range.endInclusive)
        resumePreviewLocked()
        zoomRatio
    }

    val associator = CaptureResultAssociator()
    val frameRepository = BoundedRingFrameRepository()

    // Capability resolver and discovered profiles
    val capabilityResolver = DeviceCapabilityResolver(context, cameraManager)
    private var discoveredProfiles: Map<String, DiscoveredCameraProfile> = emptyMap()

    // Bounded retry counter
    private val retryAttempts = AtomicInteger(0)
    private val maxRetryAttempts = 3

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            val thread = HandlerThread("Camera2BackgroundThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (e: InterruptedException) {
            // Ignore interruption
        }
        cameraThread = null
        cameraHandler = null
    }

    override fun getAvailableCameras(): List<CameraProfile> {
        return emptyList()
    }

    override fun getCameraProfile(cameraId: String): CameraProfile? {
        return null
    }

    fun discoverHardwareCapabilities(): Map<String, DiscoveredCameraProfile> {
        discoveredProfiles = capabilityResolver.enumerateCameras()
        return discoveredProfiles
    }

    fun setPreviewSurface(surface: Surface?) {
        this.previewSurface = surface
    }

    @SuppressLint("MissingPermission")
    override suspend fun openCamera(cameraId: String): Boolean = mutex.withLock {
        if (retryAttempts.get() >= maxRetryAttempts) {
            stateMachine.transitionTo(CameraState.ERROR)
            _operationalState.value = CameraOperationalState.ERROR
            return false
        }

        activeCameraId = cameraId
        stateMachine.transitionTo(CameraState.INITIALIZING)
        _operationalState.value = CameraOperationalState.OPENING

        val openLatch = CountDownLatch(1)
        var openSuccess = false

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    openSuccess = true
                    retryAttempts.set(0)
                    openLatch.countDown()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    handleCameraFailure(CameraSystemError.CameraHardwareFailure.CameraDisconnected(cameraId))
                    openLatch.countDown()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    val err = CameraSystemError.CameraHardwareFailure.DeviceError(error, "HAL error code $error")
                    handleCameraFailure(err)
                    openLatch.countDown()
                }
            }, cameraHandler)

            openLatch.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            handleCameraFailure(e)
            return false
        }

        return openSuccess
    }

    suspend fun configureSession(previewSurface: Surface): Boolean = mutex.withLock {
        val device = cameraDevice ?: return false
        this.previewSurface = previewSurface

        stateMachine.transitionTo(CameraState.INITIALIZING)

        val configLatch = CountDownLatch(1)
        var sessionSuccess = false

        try {
            // Burst stream: the largest YUV_420_888 size within the copy budget. Images are copied and closed in the
            // listener, so a few reader slots are enough whatever the burst length.
            val chars = cameraManager.getCameraCharacteristics(activeCameraId ?: return false)
            val size = YuvBurstSupport.chooseSize(chars, BURST_MAX_PIXELS)
                ?: throw IllegalStateException("camera ${activeCameraId} lists no YUV_420_888 output")
            burstSize = size
            burstReader?.close()
            burstReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, BURST_READER_SLOTS).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    while (true) {
                        val image = try { r.acquireNextImage() } catch (e: Exception) { null } ?: break
                        try {
                            val collector = activeCollector
                            if (collector != null) collector.onImage(image.timestamp, CopiedYuv.copyOf(image))
                        } finally {
                            image.close() // always return the buffer, even when no burst is waiting
                        }
                    }
                }, cameraHandler)
            }

            val surfaces = listOfNotNull(previewSurface, burstReader?.surface)

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    sessionSuccess = true
                    stateMachine.transitionTo(CameraState.READY)
                    _operationalState.value = CameraOperationalState.PREVIEW_STREAMING
                    configLatch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionSuccess = false
                    stateMachine.transitionTo(CameraState.ERROR)
                    _operationalState.value = CameraOperationalState.ERROR
                    configLatch.countDown()
                }
            }, cameraHandler)

            configLatch.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            handleCameraFailure(e)
            return false
        }

        return sessionSuccess
    }

    override suspend fun startRepeatingPreview(): Boolean = mutex.withLock {
        val session = captureSession ?: return false
        val device = cameraDevice ?: return false
        val surface = previewSurface ?: return false

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            }

            session.setRepeatingRequest(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    associator.onCaptureResultArrived(timestamp, result) { imageHandle, totalResult ->
                        // Association callback
                    }
                }
            }, cameraHandler)
            return true
        } catch (e: Exception) {
            handleCameraFailure(e)
            return false
        }
    }

    override suspend fun stopRepeating() {
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            // Ignore stop errors
        }
    }

    /**
     * Captures a real burst from the camera. Returns only frames that were captured and paired with their capture result
     * by sensor timestamp; frames the camera failed or lost are absent. The count may be lower than requested (memory
     * budget, lost frames) and an empty list means nothing was captured. Frames are camera-processed YUV, not RAW:
     * the HAL has already applied its own noise reduction, so this is not the RAW burst the project targets (Gate 1).
     */
    override suspend fun triggerBurstCapture(
        frameCount: Int,
        bracketSteps: List<Int>
    ): List<CameraFrame> = mutex.withLock {
        val device = cameraDevice ?: return emptyList()
        val session = captureSession ?: return emptyList()
        val reader = burstReader ?: return emptyList()
        val size = burstSize ?: return emptyList()
        val cameraId = activeCameraId ?: return emptyList()
        if (bracketSteps.isNotEmpty()) return emptyList() // exposure bracketing is not wired; refuse rather than ignore

        val count = YuvBurstSupport.framesWithinBudget(frameCount, size, BURST_COPY_BUDGET_BYTES)
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val collector = BurstCollector<CopiedYuv, TotalCaptureResult>(count)

        stateMachine.transitionTo(CameraState.CAPTURING)
        _operationalState.value = CameraOperationalState.CAPTURING_BURST
        activeCollector = collector
        try {
            session.stopRepeating() // let the burst own the pipeline; preview resumes below
            val requests = (0 until count).map {
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                }.build()
            }
            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (ts != null) collector.onResult(ts, result) else collector.onFrameLost()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    collector.onFrameLost()
                }

                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    collector.onFrameLost()
                }
            }, cameraHandler)

            val outcome = collector.await(BURST_TIMEOUT_MS)
            return outcome.pairs.map { pair ->
                val md = YuvBurstSupport.metadataOf(pair.result, chars, cameraId, pair.timestampNs)
                CameraFrame(
                    frameId = "burst_${md.frameSequence}_${pair.timestampNs}",
                    format = "YUV_420_888",
                    width = pair.image.width,
                    height = pair.image.height,
                    planes = pair.image.planes,
                    metadata = md
                )
            }
        } catch (e: Exception) {
            handleCameraFailure(e)
            return emptyList()
        } finally {
            collector.finish()
            activeCollector = null
            stateMachine.transitionTo(CameraState.READY)
            _operationalState.value = CameraOperationalState.PREVIEW_STREAMING
            resumePreviewLocked()
        }
    }

    /** Restarts the repeating preview request; the caller holds [mutex]. */
    private fun resumePreviewLocked() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            // Preview is best effort here; the next onResume restarts it.
        }
    }

    override suspend fun closeCamera(): Unit = mutex.withLock {
        stateMachine.transitionTo(CameraState.CLOSED)
        _operationalState.value = CameraOperationalState.CLOSING

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            // Safe close
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            // Safe close
        }
        cameraDevice = null

        activeCollector?.finish()
        activeCollector = null
        burstReader?.close()
        burstReader = null

        analysisImageReader?.close()
        analysisImageReader = null

        associator.clear()
        _operationalState.value = CameraOperationalState.UNINITIALIZED
    }

    private fun handleCameraFailure(cause: Throwable) {
        val attempts = retryAttempts.incrementAndGet()
        stateMachine.forceError("Camera failure (attempt $attempts of $maxRetryAttempts): ${cause.message}")
        _operationalState.value = CameraOperationalState.ERROR

        if (attempts <= maxRetryAttempts) {
            stateMachine.transitionTo(CameraState.RECOVERING)
            // Bounded recovery: clean up resources and prepare for retry
            try {
                captureSession?.close()
                cameraDevice?.close()
            } catch (e: Exception) {
                // Ignore
            }
            captureSession = null
            cameraDevice = null
        }
    }

    fun release() {
        stopBackgroundThread()
    }

    private companion object {
        /** Largest burst frame size; merge time grows with it (roughly 0.5 s per megapixel per frame on a host JVM). */
        const val BURST_MAX_PIXELS = 4_200_000L
        const val BURST_READER_SLOTS = 4
        /** Heap held by the copied frames of one burst. */
        const val BURST_COPY_BUDGET_BYTES = 100L * 1024 * 1024
        const val BURST_TIMEOUT_MS = 8_000L
    }
}
