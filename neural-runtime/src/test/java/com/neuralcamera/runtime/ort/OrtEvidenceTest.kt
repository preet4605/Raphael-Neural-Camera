package com.neuralcamera.runtime.ort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrtEvidenceTest {

    private val qnn = OrtProfile.QNN_PROVIDER
    private val cpu = OrtProfile.CPU_PROVIDER

    @Test
    fun profileSummaryCountsNodeEventsPerProviderAndTreatsMissingProviderAsUnknown() {
        // Hand-written fixture in ONNX Runtime's Chrome-trace profile shape (not captured from a device).
        val json = """
            [
              {"cat":"Session","name":"model_loading_uri","args":{}},
              {"cat":"Node","name":"a_kernel_time","args":{"provider":"$qnn","op_name":"QNN"}},
              {"cat":"Node","name":"b_kernel_time","args":{"provider":"$qnn"}},
              {"cat":"Node","name":"c_kernel_time","args":{"provider":"$cpu"}},
              {"cat":"Node","name":"d_kernel_time","args":{}}
            ]
        """.trimIndent()

        val summary = OrtProfile.summarize(json)

        assertEquals(4, summary.nodeEvents)
        assertEquals(mapOf(qnn to 2, cpu to 1, "UNKNOWN" to 1), summary.providerCounts)
    }

    @Test
    fun profileThatIsNotAnArrayIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { OrtProfile.summarize("{\"cat\":\"Node\"}") }
        assertThrows(IllegalArgumentException::class.java) { OrtProfile.summarize("not json") }
    }

    @Test
    fun procMapsExtractsDistinctSortedQnnLibraryNames() {
        val maps = """
            7f0000000000-7f0000100000 r-xp 00000000 00:00 0 /data/app/x/lib/arm64/libQnnHtp.so
            7f0000100000-7f0000200000 r--p 00100000 00:00 0 /data/app/x/lib/arm64/libQnnHtp.so
            7f0000200000-7f0000300000 r-xp 00000000 00:00 0 /data/app/x/lib/arm64/libQnnHtpV81Stub.so
            7f0000300000-7f0000400000 r-xp 00000000 00:00 0 /vendor/lib64/libcdsprpc.so
            7f0000400000-7f0000500000 r-xp 00000000 00:00 0 /system/lib64/libc.so
            7f0000500000-7f0000600000 rw-p 00000000 00:00 0
        """.trimIndent()

        assertEquals(
            listOf("libQnnHtp.so", "libQnnHtpV81Stub.so", "libcdsprpc.so"),
            ProcMaps.mappedLibraries(maps)
        )
    }

    @Test
    fun htpAttributionIsProvenOnlyWhenEveryConditionHolds() {
        val libs = listOf("libQnnHtp.so", "libQnnHtpV81Stub.so", "libcdsprpc.so")
        assertTrue(HtpAttributionRule.failures(true, "htp", true, mapOf(qnn to 63), libs).isEmpty())
    }

    @Test
    fun eachMissingConditionBlocksHtpAttribution() {
        val libs = listOf("libQnnHtp.so")
        val ok = mapOf(qnn to 10)

        fun reasons(fallbackDisabled: Boolean = true, backend: String? = "htp", profiling: Boolean = true,
                    counts: Map<String, Int> = ok, mapped: List<String> = libs) =
            HtpAttributionRule.failures(fallbackDisabled, backend, profiling, counts, mapped)

        assertTrue(reasons(fallbackDisabled = false).any { it.contains("fallback") })
        assertTrue(reasons(backend = "cpu").any { it.contains("backend_type") })
        assertTrue(reasons(backend = null).any { it.contains("backend_type") })
        assertTrue(reasons(profiling = false).any { it.contains("profiling") })
        assertTrue(reasons(counts = emptyMap()).any { it.contains("no profiled node") })
        assertTrue(reasons(counts = mapOf(qnn to 10, cpu to 1)).any { it.contains("another provider") })
        assertTrue(reasons(counts = mapOf(cpu to 5)).isNotEmpty())
        assertTrue(reasons(counts = mapOf(qnn to 4, "UNKNOWN" to 1)).any { it.contains("another provider") })
        assertTrue(reasons(mapped = listOf("libQnnGpu.so", "libcdsprpc.so")).any { it.contains("libQnnHtp") })
        assertTrue(reasons(mapped = emptyList()).any { it.contains("libQnnHtp") })
    }
}
