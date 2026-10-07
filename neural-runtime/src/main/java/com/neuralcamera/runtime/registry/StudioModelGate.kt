package com.neuralcamera.runtime.registry

import com.neuralcamera.models.ModelCompatibilityState
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.models.SemanticPurpose

sealed class StudioAdmission {
    data class Admitted(val artifact: ModelArtifact) : StudioAdmission()
    data class Refused(val reason: String) : StudioAdmission()
}

/**
 * Which models AI Studio may load: GENERATIVE models only, and only an installed artifact whose compatibility is
 * VERIFIED (a passing device record on its exact stack, see [ModelArtifact.compatibility]). Nothing is VERIFIED today,
 * so every Studio model is refused until a device run proves it.
 */
object StudioModelGate {
    fun admit(descriptor: ModelDescriptor, artifact: ModelArtifact?): StudioAdmission {
        if (descriptor.purpose != SemanticPurpose.GENERATIVE) {
            return StudioAdmission.Refused("${descriptor.modelId} is a ${descriptor.purpose} model; Studio loads GENERATIVE models only")
        }
        if (artifact == null) return StudioAdmission.Refused("${descriptor.modelId} has no installed artifact")
        if (artifact.modelId != descriptor.modelId) {
            return StudioAdmission.Refused("artifact ${artifact.modelId} does not belong to ${descriptor.modelId}")
        }
        val state = artifact.compatibility
        if (state != ModelCompatibilityState.VERIFIED) {
            return StudioAdmission.Refused("${descriptor.modelId} ${artifact.version} is $state on ${artifact.stack.backend}; Studio loads VERIFIED artifacts only")
        }
        return StudioAdmission.Admitted(artifact)
    }
}
