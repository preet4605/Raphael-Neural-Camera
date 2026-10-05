package com.neuralcamera.runtime.reference

import com.neuralcamera.runtime.ModelArtifacts
import com.neuralcamera.runtime.TensorData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic denoise-shaped test network: conv3x3(1->8)+ReLU, conv3x3(8->8)+ReLU, conv3x3(8->1), plus a residual
 * add with the input. Weights are untrained (see tools/model_gen/gen_denoise_tiny.py); the network exists to validate
 * numerics, latency and backend attribution, and must never enter the camera path.
 */
object DenoiseTinyV1 {
    const val MODEL_ID = "denoise-tiny-v1"
    const val HEIGHT = 256
    const val WIDTH = 256
    const val WEIGHTS_RESOURCE = "denoise_tiny_v1.weights.f32"
    const val FP32_ONNX = "denoise_tiny_v1_fp32.onnx"
    const val QDQ_A16W8_ONNX = "denoise_tiny_v1_qdq_a16w8.onnx"
    const val QDQ_A8W8_ONNX = "denoise_tiny_v1_qdq_a8w8.onnx"
    const val INPUT_NAME = "input"
    const val OUTPUT_NAME = "output"

    val INPUT_SHAPE: IntArray get() = intArrayOf(1, 1, HEIGHT, WIDTH)

    internal data class Layer(val inChannels: Int, val outChannels: Int, val relu: Boolean)

    internal val LAYERS = listOf(Layer(1, 8, true), Layer(8, 8, true), Layer(8, 1, false))

    /** w1,b1,w2,b2,w3,b3 element count: (72+8)+(576+8)+(72+1). */
    const val WEIGHT_COUNT = 737

    /** Loads the committed weights blob (little-endian FP32). */
    fun loadReference(): ReferenceModel {
        val bytes = ModelArtifacts.readResource(WEIGHTS_RESOURCE)
        require(bytes.size == WEIGHT_COUNT * 4) { "weights blob has ${bytes.size} bytes, expected ${WEIGHT_COUNT * 4}" }
        val floats = FloatArray(WEIGHT_COUNT)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return DenoiseTinyV1Network(floats)
    }
}

internal class DenoiseTinyV1Network(private val weights: FloatArray) : ReferenceModel {
    override val modelId: String = DenoiseTinyV1.MODEL_ID

    init {
        require(weights.size == DenoiseTinyV1.WEIGHT_COUNT) { "expected ${DenoiseTinyV1.WEIGHT_COUNT} weights, got ${weights.size}" }
    }

    override fun run(input: TensorData): TensorData {
        val shape = input.shape
        require(shape.size == 4 && shape[0] == 1 && shape[1] == 1 && shape[2] > 0 && shape[3] > 0) {
            "expected FP32 input shape [1,1,H,W], got ${shape.contentToString()}"
        }
        val h = shape[2]
        val w = shape[3]
        val x = input.toFloatArray()
        require(x.size == h * w) { "input has ${x.size} values for shape ${shape.contentToString()}" }

        var activation = x
        var offset = 0
        for (layer in DenoiseTinyV1.LAYERS) {
            val wCount = layer.outChannels * layer.inChannels * 9
            activation = conv3x3Same(activation, layer.inChannels, h, w, weights, offset, offset + wCount, layer.outChannels, layer.relu)
            offset += wCount + layer.outChannels
        }
        val out = FloatArray(h * w) { x[it] + activation[it] }
        return TensorData.ofFloats(shape.copyOf(), out)
    }
}

/**
 * 3x3 convolution, stride 1, zero padding 1, NCHW with batch 1, weights OIHW. Accumulates in double, rounds to FP32.
 */
internal fun conv3x3Same(
    input: FloatArray,
    inChannels: Int,
    h: Int,
    w: Int,
    weights: FloatArray,
    weightOffset: Int,
    biasOffset: Int,
    outChannels: Int,
    relu: Boolean
): FloatArray {
    val plane = h * w
    val out = FloatArray(outChannels * plane)
    for (oc in 0 until outChannels) {
        val bias = weights[biasOffset + oc].toDouble()
        val weightBase = weightOffset + oc * inChannels * 9
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = bias
                for (ic in 0 until inChannels) {
                    val inBase = ic * plane
                    val wb = weightBase + ic * 9
                    for (ky in 0 until 3) {
                        val yy = y + ky - 1
                        if (yy < 0 || yy >= h) continue
                        for (kx in 0 until 3) {
                            val xx = x + kx - 1
                            if (xx < 0 || xx >= w) continue
                            acc += weights[wb + ky * 3 + kx].toDouble() * input[inBase + yy * w + xx].toDouble()
                        }
                    }
                }
                var v = acc.toFloat()
                if (relu && v < 0f) v = 0f
                out[oc * plane + y * w + x] = v
            }
        }
    }
    return out
}
