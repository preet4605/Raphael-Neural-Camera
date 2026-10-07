package com.neuralcamera.runtime.reference

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.runtime.BackendAttribution
import com.neuralcamera.runtime.InferenceBackend
import com.neuralcamera.runtime.InferenceOutput
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.UnsupportedModelException

/**
 * Golden FP32 CPU backend. Runs only models that have a [ReferenceModel]; it is the correctness oracle for the
 * accelerated backends, not a performance path, and it can never be accelerator evidence.
 */
class CpuReferenceBackend(
    models: List<ReferenceModel> = listOf(DenoiseTinyV1.loadReference())
) : InferenceBackend {

    private val byId = models.associateBy { it.modelId }

    override val backendType: HardwareBackendType = HardwareBackendType.CPU_REFERENCE

    override fun isAvailable(): Boolean = true

    override suspend fun initialize(): Boolean = true

    override suspend fun prepare(model: ModelDescriptor): Boolean = byId.containsKey(model.modelId)

    override suspend fun executeInference(model: ModelDescriptor, input: TensorData): InferenceOutput {
        val reference = byId[model.modelId]
            ?: throw UnsupportedModelException("No CPU reference implementation for model ${model.modelId}")
        return InferenceOutput(reference.run(input), BackendAttribution.cpuReference())
    }

    override suspend fun finalizeAttribution(): BackendAttribution = BackendAttribution.cpuReference()

    override fun release() {}
}
