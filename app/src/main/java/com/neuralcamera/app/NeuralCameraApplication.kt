package com.neuralcamera.app

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.PowerManager
import com.neuralcamera.benchmarks.InMemoryTelemetryLogger
import com.neuralcamera.benchmarks.StandardBenchmarkRunner
import com.neuralcamera.cameracore.DeviceCapabilityResolver
import com.neuralcamera.cameracore.LogicalToPhysicalCameraMap
import com.neuralcamera.cameracore.RealCamera2Controller
import com.neuralcamera.cameracore.SensorTimelineSynchronizer
import com.neuralcamera.capture.UniversalCapturePlanner
import com.neuralcamera.capture.policy.DeviceConditions
import com.neuralcamera.deviceprofiles.InMemoryDeviceProfileRepository
import com.neuralcamera.deviceprofiles.PredefinedDeviceProfiles
import com.neuralcamera.gallery.OriginalMasterMediaRepository
import com.neuralcamera.isp.BaselineImagePipeline
import com.neuralcamera.quality.StandardConfidenceEstimator
import com.neuralcamera.quality.StandardQualityEvaluator
import com.neuralcamera.quality.StandardRealityGuard
import com.neuralcamera.runtime.AdaptivePipelineScheduler
import com.neuralcamera.runtime.StandardComputeBudgetManager
import com.neuralcamera.runtime.StandardMemoryManager
import com.neuralcamera.runtime.StandardModelRegistry
import com.neuralcamera.runtime.StandardThermalManager
import com.neuralcamera.runtime.ThermalState
import java.io.File

class NeuralCameraApplication : Application() {

    lateinit var deviceProfileRepository: InMemoryDeviceProfileRepository
    lateinit var modelRegistry: StandardModelRegistry
    lateinit var thermalManager: StandardThermalManager
    lateinit var memoryManager: StandardMemoryManager
    lateinit var computeBudgetManager: StandardComputeBudgetManager
    lateinit var pipelineScheduler: AdaptivePipelineScheduler
    lateinit var capturePlanner: UniversalCapturePlanner
    lateinit var imagePipeline: BaselineImagePipeline
    lateinit var mediaRepository: OriginalMasterMediaRepository
    lateinit var telemetryLogger: InMemoryTelemetryLogger
    lateinit var benchmarkRunner: StandardBenchmarkRunner

    // Phase 1 Real Hardware Acquisition Layer Components
    lateinit var cameraController: RealCamera2Controller
    lateinit var sensorSynchronizer: SensorTimelineSynchronizer
    lateinit var capabilityResolver: DeviceCapabilityResolver
    lateinit var logicalToPhysicalMap: LogicalToPhysicalCameraMap

    private var powerManager: PowerManager? = null
    private var batteryManager: BatteryManager? = null
    /** Latest PowerManager thermal status (THERMAL_STATUS_NONE..SHUTDOWN), from the system listener. */
    @Volatile private var thermalStatus: Int? = null

    /** Current device conditions for capture policy; fields the system does not report stay null (never assumed). */
    fun deviceConditions(): DeviceConditions = DeviceConditions(
        thermalStatus = thermalStatus,
        batteryPercent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
        powerSaveMode = powerManager?.isPowerSaveMode
    )

    private fun onThermalStatus(status: Int) {
        thermalStatus = status
        thermalManager.updateThermalState(ThermalState.entries.firstOrNull { it.level == status } ?: ThermalState.NONE)
        telemetryLogger.logEvent("THERMAL_STATUS", mapOf("status" to status))
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Device Profile (OnePlus 15 default target)
        deviceProfileRepository = InMemoryDeviceProfileRepository(PredefinedDeviceProfiles.ONEPLUS_15)
        val activeProfile = deviceProfileRepository.getActiveProfile()

        // 2. Telemetry and Benchmarks
        telemetryLogger = InMemoryTelemetryLogger()
        benchmarkRunner = StandardBenchmarkRunner()

        // 3. Model & Runtime managers
        modelRegistry = StandardModelRegistry()
        thermalManager = StandardThermalManager(activeProfile)
        memoryManager = StandardMemoryManager(activeProfile.memoryLimits.maxModelResidencyBytes)
        computeBudgetManager = StandardComputeBudgetManager()

        // 4. Compute Scheduler
        pipelineScheduler = AdaptivePipelineScheduler(
            modelRegistry = modelRegistry,
            thermalManager = thermalManager,
            memoryManager = memoryManager,
            computeBudgetManager = computeBudgetManager,
            deviceProfile = activeProfile
        )

        // 5. Capture Intelligence
        capturePlanner = UniversalCapturePlanner()

        // 6. Quality & Neural ISP
        val qualityEval = StandardQualityEvaluator()
        val confEstimator = StandardConfidenceEstimator()
        val realityGuard = StandardRealityGuard()
        imagePipeline = BaselineImagePipeline(
            qualityEvaluator = qualityEval,
            confidenceEstimator = confEstimator,
            realityGuard = realityGuard,
            benchmarkRunner = benchmarkRunner
        )

        // 7. Gallery / Storage
        val mediaDir = File(filesDir, "captures")
        mediaRepository = OriginalMasterMediaRepository(mediaDir)

        // 8. Phase 1 Real Camera2 & Sensor Layer
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        cameraController = RealCamera2Controller(this, cameraManager)
        sensorSynchronizer = SensorTimelineSynchronizer(sensorManager)
        capabilityResolver = DeviceCapabilityResolver(this, cameraManager)
        logicalToPhysicalMap = LogicalToPhysicalCameraMap(cameraManager)

        // 9. Device conditions: real thermal status from the system drives the thermal manager and capture policy.
        powerManager = getSystemService(PowerManager::class.java)
        batteryManager = getSystemService(BatteryManager::class.java)
        powerManager?.let { pm ->
            onThermalStatus(pm.currentThermalStatus)
            pm.addThermalStatusListener(mainExecutor) { onThermalStatus(it) }
        }

        telemetryLogger.logEvent("APP_INITIALIZED", mapOf(
            "device" to activeProfile.deviceModel,
            "soc" to activeProfile.socFamily,
            "ram_gb" to (activeProfile.totalRamBytes / (1024 * 1024 * 1024))
        ))
    }
}
