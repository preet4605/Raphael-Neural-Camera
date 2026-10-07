package com.neuralcamera.runtime.registry

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.PredefinedModelCatalog
import com.neuralcamera.models.SemanticPurpose
import com.neuralcamera.models.TensorPrecision
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelGateTest {
    private val studio = PredefinedModelCatalog.FLUX_KLEIN_4B_STUDIO
    private val bytes = "studio-weights".toByteArray()
    private val sha = ModelLifecycleRegistry.sha256(bytes)
    private val stack = RuntimeStack(HardwareBackendType.VULKAN_GPU, "test-runtime 1.0", null, null, null)

    private fun artifact(verification: VerificationRecord?) =
        ModelArtifact(studio.modelId, studio.version, "flux.bin", bytes.size.toLong(), sha, TensorPrecision.INT4, "int4", stack, verification = verification)

    private fun record(passed: Boolean, runs: Int) = VerificationRecord(sha, "test device", stack, passed, runs, null, null, "report.json")

    @Test
    fun onlyAVerifiedGenerativeArtifactIsAdmitted() {
        fun refused(a: StudioAdmission) = a is StudioAdmission.Refused
        assertTrue(refused(StudioModelGate.admit(studio, null)))
        assertTrue(refused(StudioModelGate.admit(studio, artifact(null))))                 // UNVERIFIED
        assertTrue(refused(StudioModelGate.admit(studio, artifact(record(true, 2)))))      // too few runs
        assertTrue(refused(StudioModelGate.admit(studio, artifact(record(false, 3)))))     // checker failed
        assertTrue(StudioModelGate.admit(studio, artifact(record(true, 3))) is StudioAdmission.Admitted)
        // A camera-path model is never loaded by Studio, verified or not.
        val isp = studio.copy(modelId = "isp", purpose = SemanticPurpose.NEURAL_ISP)
        val r = StudioModelGate.admit(isp, artifact(record(true, 3)))
        assertTrue(r is StudioAdmission.Refused && r.reason.contains("GENERATIVE"))
    }

    @Test
    fun theCatalogStudioModelIsRefusedToday() {
        val r = StudioModelGate.admit(studio, artifact(null))
        assertTrue(r is StudioAdmission.Refused && r.reason.contains("UNVERIFIED"))
    }
}
