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
import com.neuralcamera.cameracore.orchestration.CaptureCommand
import com.neuralcamera.cameracore.orchestration.CaptureCommandExecutor
import com.neuralcamera.cameracore.orchestration.CaptureEvent
import com.neuralcamera.cameracore.orchestration.FrameRef
import com.neuralcamera.cameracore.orchestration.OrchestrationPolicy
import com.neuralcamera.cameracore.orchestration.OrchestrationResult
import com.neuralcamera.cameracore.orchestration.OrchestratorDriver
import com.neuralcamera.cameracore.threea.AeState
import com.neuralcamera.cameracore.threea.AfState
import com.neuralcamera.cameracore.threea.AwbState
import com.neuralcamera.cameracore.threea.ConvergenceDetector
import com.neuralcamera.cameracore.threea.ConvergenceParams
import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import com.neuralcamera.cameracore.threea.ExposureRecord
import com.neuralcamera.cameracore.state.CameraState
import com.neuralcamera.cameracore.state.CameraStateMachine
import com.neuralcamera.deviceprofiles.CameraProfile
import com.neuralcamera.deviceprofiles.DiscoveredCameraProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Results of the repeating preview request, read by the 3A convergence wait. Bounded: when nobody is waiting the
     * oldest results are dropped, so a stale backlog never reaches a later convergence check (it also filters by frame).
     */
    private val previewResults = Channel<TotalCaptureResult>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            associator.onCaptureResultArrived(timestamp, result) { _, _ -> }
            previewResults.trySend(result)
        }
    }

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

    suspend fun configureSession(previewSurface: Surface): Boolean = mutex.withLock { configureSessionLocked(previewSurface) }

    /** Creates the preview + burst session; the caller holds [mutex]. */
    private fun configureSessionLocked(previewSurface: Surface): Boolean {
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

            session.setRepeatingRequest(requestBuilder.build(), previewCallback, cameraHandler)
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
     * Captures a real burst and returns only its frames; see [captureBurst] for the orchestration report.
     * Exposure bracketing is not wired, so a non-empty [bracketSteps] is refused (empty list) rather than ignored.
     */
    override suspend fun triggerBurstCapture(frameCount: Int, bracketSteps: List<Int>): List<CameraFrame> {
        if (bracketSteps.isNotEmpty()) return emptyList()
        return captureBurst(frameCount).frames
    }

    /**
     * Captures a real burst through the capture orchestrator: AE precapture + AF trigger, wait for 3A convergence (from
     * the preview results), AE/AWB lock, burst, unlock and preview resume, with bounded retry and session recovery.
     *
     * Returns only frames that were captured and paired with their capture result by sensor timestamp; the count may be
     * lower than requested (memory budget, lost frames) and [BurstCapture.result] says why. Frames are camera-processed
     * YUV, not RAW (the HAL has already applied its own noise reduction), so this is not the Gate 1 RAW burst.
     * On-device behaviour (convergence time, lock stability, recovery) is NOT_TESTED.
     */
    suspend fun captureBurst(frameCount: Int, minUsableFrames: Int = 1): BurstCapture = mutex.withLock {
        val size = burstSize
        val cameraId = activeCameraId
        if (cameraDevice == null || captureSession == null || burstReader == null || size == null || cameraId == null) {
            return BurstCapture.failed(frameCount, "camera is not open and configured")
        }
        val count = YuvBurstSupport.framesWithinBudget(frameCount, size, BURST_COPY_BUDGET_BYTES)
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val executor = BurstExecutor(chars, count)
        val policy = OrchestrationPolicy(requestedFrames = count, minUsableFrames = minUsableFrames.coerceIn(1, count))

        stateMachine.transitionTo(CameraState.CAPTURING)
        _operationalState.value = CameraOperationalState.CAPTURING_BURST
        try {
            val result = OrchestratorDriver(policy, executor).run()
            val kept = result.frames.map { it.sensorTimestampNs }.toSet()
            val frames = executor.lastAttempt.filter { it.timestampNs in kept }.map { pair ->
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
            BurstCapture(frames, result, executor.convergence, executor.lockedFrames, executor.lockRequested)
        } finally {
            activeCollector?.finish()
            activeCollector = null
            if (captureSession != null) {
                stateMachine.transitionTo(CameraState.READY)
                _operationalState.value = CameraOperationalState.PREVIEW_STREAMING
                resumePreviewLocked()
            }
        }
    }

    /** Executes orchestrator commands on the open session; runs with [mutex] held by [captureBurst]. */
    private inner class BurstExecutor(private val chars: CameraCharacteristics, private val frames: Int) : CaptureCommandExecutor {
        /** Fixed-focus lenses report minimum focus distance 0 and never leave AF INACTIVE. */
        private val hasAf = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        private val aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        private val awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        private val params = ConvergenceParams(requireFocus = hasAf)
        private var triggerFrame = -1L

        var convergence: ConvergenceVerdict? = null
            private set
        var lockRequested = false
            private set
        /** Burst frames whose result reported AE LOCKED (or AE off); null until a burst ran. */
        var lockedFrames: Int? = null
            private set
        var lastAttempt: List<BurstCollector.Pair<CopiedYuv, TotalCaptureResult>> = emptyList()
            private set

        override suspend fun execute(command: CaptureCommand): List<CaptureEvent> = when (command) {
            CaptureCommand.RunPrecapture -> precapture()
            CaptureCommand.AwaitConvergence -> listOf(CaptureEvent.ConvergenceUpdate(awaitConvergence().also { convergence = it }))
            CaptureCommand.Lock3A -> {
                lockRequested = aeLockAvailable || awbLockAvailable
                if (lockRequested) resumePreviewLocked(lockAe = aeLockAvailable, lockAwb = awbLockAvailable)
                emptyList()
            }
            is CaptureCommand.IssueBurst -> burst()
            CaptureCommand.AbortCaptures -> {
                runCatching { captureSession?.abortCaptures() }
                emptyList()
            }
            CaptureCommand.RecreateSession -> recreateSession()
            CaptureCommand.Unlock3AAndResumePreview -> {
                if (hasAf) runCatching { submitSingle { set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) } }
                emptyList() // preview itself resumes, unlocked, when captureBurst returns
            }
            is CaptureCommand.Deliver -> emptyList()
        }

        /** Fires AE precapture (and AF trigger) on the preview stream; done once the trigger request completes. */
        private suspend fun precapture(): List<CaptureEvent> {
            val done = CompletableDeferred<Long?>()
            submitSingle(done) {
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                if (hasAf) set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            val frame = withTimeoutOrNull(TRIGGER_TIMEOUT_MS) { done.await() }
                ?: return listOf(CaptureEvent.SessionError(recoverable = true, reason = "precapture trigger did not complete"))
            triggerFrame = frame
            return listOf(CaptureEvent.PrecaptureFinished)
        }

        /** Feeds preview results from after the trigger into a [ConvergenceDetector]; returns a final verdict. */
        private suspend fun awaitConvergence(): ConvergenceVerdict {
            val detector = ConvergenceDetector(params)
            val deadlineNs = System.nanoTime() + params.timeoutNs + CONVERGENCE_WALL_MARGIN_NS
            var sawResult = false
            while (true) {
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) return if (sawResult) ConvergenceVerdict.TIMED_OUT else ConvergenceVerdict.UNKNOWN
                val result = withTimeoutOrNull(remainingMs) { previewResults.receive() } ?: continue
                if (result.frameNumber < triggerFrame) continue // results from before the trigger say nothing about it
                val record = exposureRecordOf(result) ?: continue
                sawResult = true
                val verdict = detector.onResult(record)
                if (verdict != ConvergenceVerdict.PENDING) return verdict
            }
        }

        private suspend fun burst(): List<CaptureEvent> {
            val device = cameraDevice ?: return listOf(CaptureEvent.SessionError(true, "camera device closed"))
            val session = captureSession ?: return listOf(CaptureEvent.SessionError(true, "session closed"))
            val reader = burstReader ?: return listOf(CaptureEvent.SessionError(true, "burst reader closed"))
            lastAttempt = emptyList()
            val collector = BurstCollector<CopiedYuv, TotalCaptureResult>(frames)
            activeCollector = collector
            session.stopRepeating() // let the burst own the pipeline; preview resumes after delivery
            val requests = (0 until frames).map {
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyAuto3A(lock3A = lockRequested)
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

            val outcome = withContext(Dispatchers.IO) { collector.await(BURST_TIMEOUT_MS) }
            activeCollector = null
            lastAttempt = outcome.pairs
            lockedFrames = outcome.pairs.count { p ->
                val ae = AeState.fromCamera2(p.result.get(CaptureResult.CONTROL_AE_STATE))
                ae == AeState.LOCKED || p.result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF
            }
            // Request indices follow sensor-timestamp order; frames that never paired are reported lost, or the whole
            // remainder times out, so the orchestrator decides between COMPLETE, PARTIAL and retry.
            val events: List<CaptureEvent> =
                outcome.pairs.mapIndexed { i, p -> CaptureEvent.FrameArrived(FrameRef(i, p.timestampNs, p.result.frameNumber)) }
            return events + if (outcome.timedOut) listOf(CaptureEvent.BurstTimeout)
            else (outcome.pairs.size until frames).map { CaptureEvent.FrameLost(it, "capture failed or buffer lost") }
        }

        private fun recreateSession(): List<CaptureEvent> {
            val surface = previewSurface ?: return listOf(CaptureEvent.SessionError(false, "no preview surface"))
            runCatching { captureSession?.close() }
            captureSession = null
            return if (configureSessionLocked(surface)) {
                resumePreviewLocked()
                listOf(CaptureEvent.SessionRecovered)
            } else listOf(CaptureEvent.SessionError(false, "session could not be recreated"))
        }

        /** One preview-surface request with [extra] settings, e.g. a 3A trigger. Completes [done] with its frame number. */
        private fun submitSingle(done: CompletableDeferred<Long?>? = null, extra: CaptureRequest.Builder.() -> Unit) {
            val device = cameraDevice ?: error("camera device closed")
            val session = captureSession ?: error("session closed")
            val surface = previewSurface ?: error("no preview surface")
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyAuto3A(lock3A = false)
                extra()
            }.build()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    done?.complete(result.frameNumber)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    done?.complete(null)
                }
            }, cameraHandler)
        }

        private fun CaptureRequest.Builder.applyAuto3A(lock3A: Boolean) {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            if (lock3A && aeLockAvailable) set(CaptureRequest.CONTROL_AE_LOCK, true)
            if (lock3A && awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    /** Restarts the repeating preview request, optionally with AE and/or AWB locked; the caller holds [mutex]. */
    private fun resumePreviewLocked(lockAe: Boolean = false, lockAwb: Boolean = false) {
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
                if (lockAe) set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (lockAwb) set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
        } catch (e: Exception) {
            // Preview is best effort here; the next onResume restarts it.
        }
    }

    /** Facts from one capture result for 3A decisions; null when the result has no sensor timestamp. */
    private fun exposureRecordOf(r: CaptureResult): ExposureRecord? {
        val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return null
        return ExposureRecord(
            frameNumber = r.frameNumber,
            sensorTimestampNs = ts,
            exposureTimeNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivityIso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            postRawBoost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
            frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION),
            aperture = r.get(CaptureResult.LENS_APERTURE),
            aeState = AeState.fromCamera2(r.get(CaptureResult.CONTROL_AE_STATE)),
            afState = AfState.fromCamera2(r.get(CaptureResult.CONTROL_AF_STATE)),
            awbState = AwbState.fromCamera2(r.get(CaptureResult.CONTROL_AWB_STATE)),
            aeLocked = r.get(CaptureResult.CONTROL_AE_LOCK),
            awbLocked = r.get(CaptureResult.CONTROL_AWB_LOCK),
            focusDistanceDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
            manualExposure = r.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF
        )
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
        /** Wait for the 3A trigger request itself to complete. */
        const val TRIGGER_TIMEOUT_MS = 1_000L
        /** Wall-clock slack over the detector's sensor-time timeout, for when results stop arriving. */
        const val CONVERGENCE_WALL_MARGIN_NS = 500_000_000L
    }
}
