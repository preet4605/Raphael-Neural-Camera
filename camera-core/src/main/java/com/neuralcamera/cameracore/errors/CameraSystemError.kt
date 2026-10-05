package com.neuralcamera.cameracore.errors

/**
 * Structured error model mandated by Section 11 of the Neural Camera Constitution.
 * Distinguishes expected unsupported capabilities, recoverable runtime failures,
 * camera hardware failures, resource constraints, and fatal application crashes.
 */
sealed class CameraSystemError(
    val code: String,
    override val message: String,
    val isRecoverable: Boolean,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Expected unsupported capability (e.g. RAW not supported, stream combination missing).
     */
    sealed class ExpectedUnsupportedCapability(
        code: String,
        message: String,
        cause: Throwable? = null
    ) : CameraSystemError(code, message, isRecoverable = true, cause) {
        class RawFormatUnsupported(cameraId: String) :
            ExpectedUnsupportedCapability("ERR_CAP_RAW_UNSUPPORTED", "Camera $cameraId does not support RAW_SENSOR output.")

        class StreamCombinationUnsupported(description: String) :
            ExpectedUnsupportedCapability("ERR_CAP_STREAM_COMBO", "Stream combination unsupported: $description")

        class HdrUnsupported(cameraId: String) :
            ExpectedUnsupportedCapability("ERR_CAP_HDR_UNSUPPORTED", "Camera $cameraId does not support 10-bit HDR capture.")
    }

    /**
     * Recoverable runtime failure (e.g. AI backend init failure, inference timeout).
     */
    sealed class RecoverableRuntimeFailure(
        code: String,
        message: String,
        cause: Throwable? = null
    ) : CameraSystemError(code, message, isRecoverable = true, cause) {
        class BackendInitFailed(backendName: String, reason: String) :
            RecoverableRuntimeFailure("ERR_RUN_BACKEND_INIT", "Failed to initialize backend $backendName: $reason")

        class InferenceTimeout(modelId: String, timeoutMs: Long) :
            RecoverableRuntimeFailure("ERR_RUN_INFERENCE_TIMEOUT", "Inference timed out for model $modelId after ${timeoutMs}ms")

        class ThermalThrottled(currentLevel: Int) :
            RecoverableRuntimeFailure("ERR_RUN_THERMAL_THROTTLED", "Execution throttled due to thermal state level $currentLevel")
    }

    /**
     * Camera hardware failure (e.g. camera disconnected, in use by another app).
     */
    sealed class CameraHardwareFailure(
        code: String,
        message: String,
        cause: Throwable? = null
    ) : CameraSystemError(code, message, isRecoverable = true, cause) {
        class CameraDisconnected(cameraId: String) :
            CameraHardwareFailure("ERR_CAM_DISCONNECTED", "Camera $cameraId was unexpectedly disconnected.")

        class CameraInUse(cameraId: String) :
            CameraHardwareFailure("ERR_CAM_IN_USE", "Camera $cameraId is currently in use by another process.")

        class DeviceError(errorCode: Int, message: String) :
            CameraHardwareFailure("ERR_CAM_DEVICE_ERROR", "Camera device error $errorCode: $message")
    }

    /**
     * Resource failure (e.g. insufficient memory/storage, buffer overflow).
     */
    sealed class ResourceFailure(
        code: String,
        message: String,
        isRecoverable: Boolean = false,
        cause: Throwable? = null
    ) : CameraSystemError(code, message, isRecoverable, cause) {
        class InsufficientMemory(requiredBytes: Long, availableBytes: Long) :
            ResourceFailure("ERR_RES_MEMORY", "Insufficient memory: required $requiredBytes, available $availableBytes", isRecoverable = false)

        class InsufficientStorage(requiredBytes: Long, availableBytes: Long) :
            ResourceFailure("ERR_RES_STORAGE", "Insufficient disk storage: required $requiredBytes, available $availableBytes", isRecoverable = true)

        class BufferRingOverflow(capacity: Int) :
            ResourceFailure("ERR_RES_BUFFER_OVERFLOW", "Frame ring buffer overflow at capacity $capacity", isRecoverable = true)
    }

    /**
     * Fatal application failure (e.g. unrecoverable initialization failure).
     */
    sealed class FatalApplicationFailure(
        code: String,
        message: String,
        cause: Throwable? = null
    ) : CameraSystemError(code, message, isRecoverable = false, cause) {
        class UnrecoverableInitFailure(subsystem: String, reason: String) :
            FatalApplicationFailure("ERR_FATAL_INIT", "Subsystem $subsystem failed to initialize: $reason")

        class SubsystemCrash(subsystem: String, cause: Throwable) :
            FatalApplicationFailure("ERR_FATAL_CRASH", "Critical crash in subsystem $subsystem", cause)
    }
}
