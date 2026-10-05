package com.neuralcamera.runtime.ort

import ai.onnxruntime.OrtEnvironment
import com.neuralcamera.models.PredefinedModelCatalog
import com.neuralcamera.runtime.ExecutionTarget
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.proof.DeterministicInput
import com.neuralcamera.runtime.reference.CpuReferenceBackend
import com.neuralcamera.runtime.reference.DenoiseTinyV1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the ONNX Runtime CPU EP on a host JVM. These tests need ORT's native library, so they are skipped where it
 * cannot load (for example Gradle unit tests that only see the Android AAR). They never exercise the HTP.
 */
class OrtBackendHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val model = PredefinedModelCatalog.DENOISE_TINY_V1

    private fun ortAvailable() = try {
        OrtEnvironment.getEnvironment()
        true
    } catch (t: Throwable) {
        false
    }

    private fun input(seed: Long = 11) = TensorData.ofFloats(
        DenoiseTinyV1.INPUT_SHAPE,
        DeterministicInput.noisyScene(DenoiseTinyV1.HEIGHT, DenoiseTinyV1.WIDTH, seed)
    )

    private fun cpuConfig(resource: String, profile: Boolean = false) = OrtBackendConfig(
        execution = OrtExecution.CPU,
        modelId = DenoiseTinyV1.MODEL_ID,
        modelResource = resource,
        enableProfiling = profile,
        profilePathPrefix = if (profile) File(tmp.root, "ort_profile").path else null
    )

    private fun psnr(a: FloatArray, b: FloatArray): Double {
        var mse = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i].toDouble()
            mse += d * d
        }
        mse /= a.size
        return if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * Math.log10(1.0 / mse)
    }

    private fun referenceOutput(x: TensorData): FloatArray = runBlocking {
        CpuReferenceBackend().executeInference(model, x).tensor.toFloatArray()
    }

    @Test
    fun ortCpuFp32MatchesTheKotlinReference() {
        assumeTrue(ortAvailable())
        runBlocking {
            val backend = OrtBackend(cpuConfig(DenoiseTinyV1.FP32_ONNX))
            assertTrue(backend.isAvailable())
            assertTrue(backend.prepare(model))
            val x = input()

            val out = backend.executeInference(model, x)

            val ref = referenceOutput(x)
            val got = out.tensor.toFloatArray()
            var maxAbs = 0.0
            for (i in ref.indices) maxAbs = maxOf(maxAbs, Math.abs(ref[i].toDouble() - got[i].toDouble()))
            assertEquals(DenoiseTinyV1.INPUT_SHAPE.toList(), out.tensor.shape.toList())
            assertTrue("max abs diff $maxAbs", maxAbs < 1e-5)
            assertTrue(psnr(got, ref) > 100.0)
            backend.release()
        }
    }

    @Test
    fun quantizedGraphsOnOrtCpuStayWithinTheGateNumericTarget() {
        assumeTrue(ortAvailable())
        runBlocking {
            for (resource in listOf(DenoiseTinyV1.QDQ_A16W8_ONNX, DenoiseTinyV1.QDQ_A8W8_ONNX)) {
                val backend = OrtBackend(cpuConfig(resource))
                assertTrue(backend.prepare(model))
                val x = input(seed = 99)

                val got = backend.executeInference(model, x).tensor.toFloatArray()

                val db = psnr(got, referenceOutput(x))
                assertTrue("$resource PSNR $db dB (CPU EP simulation of the QDQ graph, not HTP)", db >= 35.0)
                backend.release()
            }
        }
    }

    @Test
    fun cpuEpProfileAttributesToCpuRuntimeAndNeverToHtp() {
        assumeTrue(ortAvailable())
        runBlocking {
            val backend = OrtBackend(cpuConfig(DenoiseTinyV1.FP32_ONNX, profile = true))
            assertTrue(backend.prepare(model))
            repeat(3) { backend.executeInference(model, input()) }

            val attribution = backend.finalizeAttribution()

            assertEquals(ExecutionTarget.CPU_RUNTIME, attribution.target)
            assertFalse(attribution.provesHtp)
            @Suppress("UNCHECKED_CAST")
            val profile = attribution.details["ortProfile"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val counts = profile["providerCounts"] as Map<String, Int>
            assertEquals(setOf(OrtProfile.CPU_PROVIDER), counts.keys)
            assertTrue(File(profile["path"] as String).exists())
            assertTrue(attribution.wholeGraphOnTarget)
            backend.release()
        }
    }

    @Test
    fun htpConfigurationOnAHostWithoutQnnIsUnavailableAndNeverAttributed() {
        assumeTrue(ortAvailable())
        runBlocking {
            val backend = OrtBackend(
                HtpConfigs.config()
            )
            assertFalse(backend.isAvailable())
            assertFalse(backend.prepare(model))
            assertEquals(ExecutionTarget.UNKNOWN, backend.finalizeAttribution().target)
            assertFalse(backend.finalizeAttribution().provesHtp)
            assertTrue(backend.bringUpSteps().any { !it.ok })
        }
    }

    @Test
    fun wrongModelIsRejectedWithoutASession() {
        assumeTrue(ortAvailable())
        runBlocking {
            val backend = OrtBackend(cpuConfig(DenoiseTinyV1.FP32_ONNX))
            assertFalse(backend.prepare(PredefinedModelCatalog.NEURAL_ISP_LITE))
            assertNull(backend.bringUpSteps().firstOrNull { it.name == "session.create" })
        }
    }

    private object HtpConfigs {
        fun config() = OrtBackendConfig(
            execution = OrtExecution.QNN_HTP,
            modelId = DenoiseTinyV1.MODEL_ID,
            modelResource = DenoiseTinyV1.FP32_ONNX,
            enableProfiling = false
        )
    }
}
