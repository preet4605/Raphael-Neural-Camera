package com.neuralcamera.runtime.registry

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelCompatibilityState
import com.neuralcamera.models.ModelVersion
import com.neuralcamera.models.TensorPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLifecycleTest {
    private val v1 = ModelVersion(1, 0, 0)
    private val v2 = ModelVersion(1, 1, 0)
    private val bytes1 = "model-one".toByteArray()
    private val bytes2 = "model-two!".toByteArray()
    private val cpu = RuntimeStack(HardwareBackendType.ORT_CPU, "onnxruntime-android-qnn 1.29.0", null, null, null)

    private fun artifact(v: ModelVersion, b: ByteArray, stack: RuntimeStack = cpu, verification: VerificationRecord? = null) =
        ModelArtifact("denoise", v, "m.onnx", b.size.toLong(), ModelLifecycleRegistry.sha256(b), TensorPrecision.FP32, "fp32", stack,
            verification = verification)

    @Test
    fun currentQnnStackIsAMismatchAndUnverified() {
        val s = ModelLifecycleRegistry.CURRENT_QNN_STACK
        assertEquals("2.42.0", s.bundledQnnVersion)
        assertEquals("2.37.4", s.deviceQnnVersion)
        assertEquals(QnnPairing.MISMATCHED_UNVERIFIED, s.qnnPairing())
        assertEquals(ModelCompatibilityState.UNVERIFIED, artifact(v1, bytes1, s).compatibility)
    }

    @Test
    fun checksumMismatchIsRejected() {
        val r = ModelLifecycleRegistry().apply { install(artifact(v1, bytes1)) }
        val res = r.activate("denoise", v1, "tampered".toByteArray().copyOf(bytes1.size))
        assertTrue(res is ActivationResult.Rejected && res.reason.contains("checksum"))
        assertNull(r.active("denoise"))
    }

    @Test
    fun unverifiedAcceleratorIsRejectedUnlessProbing() {
        val r = ModelLifecycleRegistry().apply { install(artifact(v1, bytes1, ModelLifecycleRegistry.CURRENT_QNN_STACK)) }
        val res = r.activate("denoise", v1, bytes1)
        assertTrue(res is ActivationResult.Rejected && res.reason.contains("MISMATCHED_UNVERIFIED"))
        assertTrue(r.activate("denoise", v1, bytes1, allowUnverifiedForProbe = true) is ActivationResult.Activated)
    }

    @Test
    fun verificationNeedsCheckerPassThreeRunsAndSameStack() {
        val qnn = ModelLifecycleRegistry.CURRENT_QNN_STACK
        val sha = ModelLifecycleRegistry.sha256(bytes1)
        fun rec(passed: Boolean, runs: Int, stack: RuntimeStack = qnn) = VerificationRecord(sha, "CPH2745", stack, passed, runs, 40.0, 1L, "gate2/")
        assertEquals(ModelCompatibilityState.VERIFIED, artifact(v1, bytes1, qnn, rec(true, 3)).compatibility)
        assertEquals(ModelCompatibilityState.UNVERIFIED, artifact(v1, bytes1, qnn, rec(true, 2)).compatibility)
        assertEquals(ModelCompatibilityState.UNSUPPORTED, artifact(v1, bytes1, qnn, rec(false, 3)).compatibility)
        val otherStack = qnn.copy(bundledQnnVersion = "2.37.1")
        assertEquals(ModelCompatibilityState.UNVERIFIED, artifact(v1, bytes1, qnn, rec(true, 3, otherStack)).compatibility)
    }

    @Test
    fun activationRollbackAndLoadState() {
        val r = ModelLifecycleRegistry().apply { install(artifact(v1, bytes1)); install(artifact(v2, bytes2)) }
        assertTrue(r.activate("denoise", v1, bytes1) is ActivationResult.Activated)
        assertTrue(r.activate("denoise", v2, bytes2) is ActivationResult.Activated)
        assertEquals(v1, r.rollbackTarget("denoise")!!.version)
        r.recordLoad("denoise", LoadState.FAILED, error = "HTP context creation failed")
        assertEquals(v1, r.rollback("denoise", "load failed")!!.version)
        assertEquals(v1, r.active("denoise")!!.version)
        assertEquals(LoadState.NOT_LOADED, r.loadRecord("denoise")!!.state)
        assertNull("one rollback step only", r.rollback("denoise", "again"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameVersionWithDifferentChecksumIsRejected() {
        ModelLifecycleRegistry().apply { install(artifact(v1, bytes1)); install(artifact(v1, bytes2)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun failedLoadMustCarryError() {
        ModelLifecycleRegistry().apply { install(artifact(v1, bytes1)); activate("denoise", v1, bytes1); recordLoad("denoise", LoadState.FAILED) }
    }

    @Test
    fun cacheKeyChangesWithStack() {
        val r = ModelLifecycleRegistry()
        val a = artifact(v1, bytes1, ModelLifecycleRegistry.CURRENT_QNN_STACK)
        val b = a.copy(stack = a.stack.copy(bundledQnnVersion = "2.37.4"))
        assertNotEquals(r.cacheKey(a), r.cacheKey(b))
        assertEquals(r.cacheKey(a), r.cacheKey(a.copy()))
    }
}
