package com.neuralcamera.runtime.reference

import com.neuralcamera.runtime.TensorData

/**
 * A model with a pure-Kotlin FP32 golden implementation. Used as the correctness oracle for accelerated backends:
 * FP32 storage, double accumulation, deterministic (no threading, no platform math intrinsics).
 */
interface ReferenceModel {
    val modelId: String
    fun run(input: TensorData): TensorData
}
