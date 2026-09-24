package com.neuralcamera.capture

import com.neuralcamera.deviceprofiles.DeviceProfile
import kotlin.math.abs

class StandardMotionEstimator : MotionEstimator {

    override fun estimateMotion(
        previousLuma: ByteArray?,
        currentLuma: ByteArray,
        width: Int,
        height: Int,
        gyroSample: FloatArray?
    ): MotionVector {
        val gyroAngularVelocity = if (gyroSample != null && gyroSample.size >= 3) {
            abs(gyroSample[0]) + abs(gyroSample[1]) + abs(gyroSample[2])
        } else {
            0.0f
        }

        if (previousLuma == null || previousLuma.size != currentLuma.size) {
            return MotionVector(0f, 0f, gyroAngularVelocity, gyroAngularVelocity < 0.05f)
        }

        // Subsample optical difference
        var diffSum = 0L
        val step = 16
        var sampleCount = 0
        for (i in 0 until minOf(previousLuma.size, currentLuma.size) step step) {
            val p = previousLuma[i].toInt() and 0xFF
            val c = currentLuma[i].toInt() and 0xFF
            diffSum += abs(p - c)
            sampleCount++
        }

        val avgDiff = if (sampleCount > 0) diffSum.toFloat() / sampleCount else 0f
        val isTripod = gyroAngularVelocity < 0.03f && avgDiff < 2.0f

        return MotionVector(
            deltaX = avgDiff * 0.1f,
            deltaY = avgDiff * 0.1f,
            angularVelocityRadS = gyroAngularVelocity,
            isTripodStable = isTripod
        )
    }
}

class UniversalCapturePlanner : CapturePlanner {

    override fun planCapture(
        mode: CameraShootingMode,
        sceneLuminanceLux: Float,
        motion: MotionVector,
        deviceProfile: DeviceProfile,
        manualIso: Int?,
        manualExposureTimeNs: Long?
    ): CapturePlan {
        val primaryCamera = deviceProfile.cameras["0"]
        val hasRaw = primaryCamera?.hasRawSupport == true
        val maxBurst = deviceProfile.thermalLimits.maxBurstFramesNormal

        val isLowLight = sceneLuminanceLux < 30f
        val isHighMotion = motion.angularVelocityRadS > 0.4f || motion.deltaX > 5f

        val calculatedIso: Int
        val calculatedExposureNs: Long

        if (manualIso != null && manualExposureTimeNs != null) {
            calculatedIso = manualIso
            calculatedExposureNs = manualExposureTimeNs
        } else {
            // Auto exposure calculation
            when {
                sceneLuminanceLux > 1000f -> { // Bright sunlight
                    calculatedIso = 50
                    calculatedExposureNs = 1_000_000L // 1/1000s
                }
                sceneLuminanceLux > 200f -> { // Daylight / well lit indoor
                    calculatedIso = 100
                    calculatedExposureNs = 4_000_000L // 1/250s
                }
                sceneLuminanceLux > 30f -> { // Indoor normal
                    calculatedIso = 400
                    calculatedExposureNs = 16_000_000L // 1/60s
                }
                else -> { // Low light / Night
                    calculatedIso = if (isHighMotion) 1600 else 800
                    calculatedExposureNs = if (isHighMotion) 16_000_000L else 40_000_000L // 1/60s or 1/25s
                }
            }
        }

        val frameCount: Int
        val bracketSteps: List<Float>
        val useRaw: Boolean
        val enableFusion: Boolean

        when (mode) {
            CameraShootingMode.AUTO -> {
                frameCount = when {
                    isHighMotion -> 3 // Fast capture to avoid motion smearing
                    isLowLight -> minOf(8, maxBurst) // Multi-frame low light fusion
                    else -> 4 // Standard ZSL burst
                }
                bracketSteps = emptyList()
                useRaw = hasRaw && isLowLight
                enableFusion = frameCount > 1
            }
            CameraShootingMode.PRO -> {
                frameCount = if (isHighMotion) 1 else 3
                bracketSteps = emptyList()
                useRaw = hasRaw
                enableFusion = frameCount > 1
            }
            CameraShootingMode.MASTER -> {
                // Maximum quality pipeline: deep temporal frames + RAW stream
                frameCount = if (isHighMotion) minOf(5, maxBurst) else minOf(12, maxBurst)
                bracketSteps = if (isLowLight || sceneLuminanceLux > 1000f) listOf(-1.5f, 0.0f, +1.5f) else emptyList()
                useRaw = hasRaw
                enableFusion = true
            }
            CameraShootingMode.AUTHENTIC -> {
                // Faithful natural rendering, conservative frame count, standard exposure
                frameCount = if (isLowLight) 4 else 2
                bracketSteps = emptyList()
                useRaw = hasRaw
                enableFusion = frameCount > 1
            }
        }

        return CapturePlan(
            mode = mode,
            targetCameraId = primaryCamera?.cameraId ?: "0",
            temporalFrameCount = frameCount,
            exposureTimeNs = calculatedExposureNs,
            iso = calculatedIso,
            bracketStepsEv = bracketSteps,
            useRawStream = useRaw,
            enableTemporalFusion = enableFusion,
            reason = "Planned for $mode mode under ${sceneLuminanceLux.toInt()} lux (motion=${motion.angularVelocityRadS} rad/s)"
        )
    }
}
