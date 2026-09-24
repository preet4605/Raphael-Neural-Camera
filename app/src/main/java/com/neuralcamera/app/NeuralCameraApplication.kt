package com.neuralcamera.app

import android.app.Application
import com.neuralcamera.benchmarks.InMemoryTelemetryLogger
import com.neuralcamera.benchmarks.StandardBenchmarkRunner
import com.neuralcamera.capture.UniversalCapturePlanner
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

        telemetryLogger.logEvent("APP_INITIALIZED", mapOf(
            "device" to activeProfile.deviceModel,
            "soc" to activeProfile.socFamily,
            "ram_gb" to (activeProfile.totalRamBytes / (1024 * 1024 * 1024))
        ))
    }
}
