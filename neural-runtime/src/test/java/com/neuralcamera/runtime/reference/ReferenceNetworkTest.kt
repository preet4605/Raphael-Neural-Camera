package com.neuralcamera.runtime.reference

import com.neuralcamera.models.PredefinedModelCatalog
import com.neuralcamera.runtime.BackendAttribution
import com.neuralcamera.runtime.ExecutionTarget
import com.neuralcamera.runtime.ModelArtifacts
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.UnsupportedModelException
import com.neuralcamera.runtime.proof.DeterministicInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceNetworkTest {

    /** Weight blob where only the listed taps are set; layout w1,b1,w2,b2,w3,b3. */
    private fun blob(w1Taps: Map<Int, Float>, w2Taps: Map<Int, Float>, w3Taps: Map<Int, Float>): FloatArray {
        val b = FloatArray(DenoiseTinyV1.WEIGHT_COUNT)
        w1Taps.forEach { (i, v) -> b[i] = v }                       // w1: 0..71
        w2Taps.forEach { (i, v) -> b[80 + i] = v }                  // w2: 80..655
        w3Taps.forEach { (i, v) -> b[80 + 584 + i] = v }            // w3: 664..735
        return b
    }

    private fun tensor(h: Int, w: Int, values: FloatArray) = TensorData.ofFloats(intArrayOf(1, 1, h, w), values)

    @Test
    fun committedWeightsLoadAndModelIsDeterministic() {
        val model = DenoiseTinyV1.loadReference()
        val input = tensor(
            DenoiseTinyV1.HEIGHT, DenoiseTinyV1.WIDTH,
            DeterministicInput.noisyScene(DenoiseTinyV1.HEIGHT, DenoiseTinyV1.WIDTH, seed = 42)
        )

        val first = model.run(input)
        val second = model.run(input)

        assertTrue(first.buffer.contentEquals(second.buffer))
        assertEquals(DenoiseTinyV1.INPUT_SHAPE.toList(), first.shape.toList())
    }

    @Test
    fun outputIsFarFromInputSoPassThroughCannotPassAGate() {
        val model = DenoiseTinyV1.loadReference()
        val x = DeterministicInput.noisyScene(DenoiseTinyV1.HEIGHT, DenoiseTinyV1.WIDTH, seed = 7)
        val y = model.run(tensor(DenoiseTinyV1.HEIGHT, DenoiseTinyV1.WIDTH, x)).toFloatArray()

        assertTrue("PSNR(input, output) must be well below the 35 dB gate", psnr(x, y) < 30.0)
        assertTrue(y.all { it.isFinite() })
    }

    @Test
    fun identityWeightsGiveTwiceTheInputForNonNegativeValues() {
        // w1: out ch0 centre tap (oc=0,ic=0,ky=1,kx=1 -> index 4); w2: ch0->ch0 centre (oc=0,ic=0 -> 4); w3: ch0 centre (4).
        val model = DenoiseTinyV1Network(blob(mapOf(4 to 1f), mapOf(4 to 1f), mapOf(4 to 1f)))
        val x = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)

        val y = model.run(tensor(2, 3, x)).toFloatArray()

        for (i in x.indices) assertEquals(2 * x[i], y[i], 1e-7f)
    }

    @Test
    fun convolutionUsesZeroPaddingAndCorrectTapOrientation() {
        // w1 tap (ky=0,kx=0) -> index 0: out[y,x] = in[y-1,x-1] (zero outside). w2/w3 centre taps pass it through.
        val model = DenoiseTinyV1Network(blob(mapOf(0 to 1f), mapOf(4 to 1f), mapOf(4 to 1f)))
        val x = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f,
            7f, 8f, 9f
        )

        val y = model.run(tensor(3, 3, x)).toFloatArray()

        val shifted = floatArrayOf(
            0f, 0f, 0f,
            0f, 1f, 2f,
            0f, 4f, 5f
        )
        for (i in x.indices) assertEquals(x[i] + shifted[i], y[i], 1e-7f)
    }

    @Test
    fun relusClampNegativeActivations() {
        // Negative first-layer tap: relu zeroes the activations, so only the residual input remains.
        val model = DenoiseTinyV1Network(blob(mapOf(4 to -1f), mapOf(4 to 1f), mapOf(4 to 1f)))
        val x = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)

        val y = model.run(tensor(2, 2, x)).toFloatArray()

        for (i in x.indices) assertEquals(x[i], y[i], 0f)
    }

    @Test
    fun rejectsWrongShapesAndWeightCounts() {
        val model = DenoiseTinyV1.loadReference()
        assertThrows(IllegalArgumentException::class.java) {
            model.run(TensorData.ofFloats(intArrayOf(1, 3, 2, 2), FloatArray(12)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            model.run(TensorData.ofFloats(intArrayOf(1, 1, 2, 2), FloatArray(3)))
        }
        assertThrows(IllegalArgumentException::class.java) { DenoiseTinyV1Network(FloatArray(10)) }
    }

    @Test
    fun cpuReferenceBackendAttributesToReferenceAndNeverToAnAccelerator() {
        runBlocking {
            val backend = CpuReferenceBackend()
            val model = PredefinedModelCatalog.DENOISE_TINY_V1
            val input = tensor(16, 16, DeterministicInput.noisyScene(16, 16, seed = 1))

            assertTrue(backend.prepare(model))
            val out = backend.executeInference(model, input)

            assertEquals(ExecutionTarget.CPU_REFERENCE, out.attribution.target)
            assertFalse(out.attribution.provesHtp)
            assertFalse(out.attribution.provesGpu)
            assertEquals(BackendAttribution.cpuReference(), out.attribution)
            assertFalse(backend.prepare(PredefinedModelCatalog.NEURAL_ISP_LITE))
            assertThrows(UnsupportedModelException::class.java) {
                runBlocking { backend.executeInference(PredefinedModelCatalog.NEURAL_ISP_LITE, input) }
            }
        }
    }

    @Test
    fun catalogEntryMatchesCommittedArtifactsAndManifest() {
        val descriptor = PredefinedModelCatalog.DENOISE_TINY_V1
        val manifest = String(ModelArtifacts.readResource("denoise_tiny_v1.manifest.json"))

        for (file in listOf(
            DenoiseTinyV1.WEIGHTS_RESOURCE, DenoiseTinyV1.FP32_ONNX, DenoiseTinyV1.QDQ_A16W8_ONNX, DenoiseTinyV1.QDQ_A8W8_ONNX
        )) {
            val bytes = ModelArtifacts.readResource(file)
            val match = Regex("\"" + Regex.escape(file) + "\":\\s*\\{\\s*\"bytes\":\\s*(\\d+),\\s*\"sha256\":\\s*\"([0-9a-f]{64})\"")
                .find(manifest)
            assertNotNull("manifest entry for $file", match)
            assertEquals("$file size", match!!.groupValues[1].toInt(), bytes.size)
            assertEquals("$file sha256", match.groupValues[2], ModelArtifacts.sha256Hex(bytes))
        }
        assertEquals(ModelArtifacts.readResource(DenoiseTinyV1.FP32_ONNX).size.toLong(), descriptor.fileSizeBytes)
        assertEquals(DenoiseTinyV1.MODEL_ID, descriptor.modelId)
    }

    private fun psnr(a: FloatArray, b: FloatArray): Double {
        var mse = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i].toDouble()
            mse += d * d
        }
        mse /= a.size
        return if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * Math.log10(1.0 / mse)
    }
}
